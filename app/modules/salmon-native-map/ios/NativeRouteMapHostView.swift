import CoreLocation
import ExpoModulesCore
import NMapsMap
import UIKit

#if DEBUG
private final class RouteNaverMapView: NMFMapView {
  var verificationValue: String?

  override var accessibilityValue: String? {
    get { verificationValue ?? super.accessibilityValue }
    set { super.accessibilityValue = newValue }
  }
}
#else
private typealias RouteNaverMapView = NMFMapView
#endif

/// A real native ancestor of the WebView. UIKit skips the entire WebKit subtree in map holes.
private final class WebOverlayGate: UIView {
  var captureAll = true
  var mapRect = CGRect.zero
  var webRects: [CGRect] = []

  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    guard !isHidden, alpha >= 0.01, isUserInteractionEnabled, bounds.contains(point) else { return nil }
    let web = captureAll || !mapRect.contains(point) || webRects.contains { $0.contains(point) }
    #if DEBUG
    if event?.type == .touches { NSLog("[SalmonNativeMap] hit target=%@", web ? "web" : "map") }
    #endif
    return web ? super.hitTest(point, with: event) : nil
  }
}

private struct RunningMotion {
  let plan: VehicleMotionPlan
  let startedAt: CFTimeInterval

  func sample(at now: CFTimeInterval) -> (MapCoordinate?, Bool) {
    guard let first = plan.path.first, let last = plan.path.last else { return (nil, false) }
    let duration = max(0, plan.durationMs) / 1000
    let progress = UIAccessibility.isReduceMotionEnabled || duration == 0
      ? 1 : min(1, max(0, (now - startedAt) / duration))
    guard progress > first.progress else { return (first.coordinate, progress < 1) }
    guard let index = plan.path.firstIndex(where: { $0.progress >= progress }), index > 0 else {
      return (last.coordinate, false)
    }
    let from = plan.path[index - 1]
    let to = plan.path[index]
    let span = to.progress - from.progress
    let fraction = span <= 0 ? 1 : (progress - from.progress) / span
    return (MapCoordinate(latitude: from.latitude + (to.latitude - from.latitude) * fraction,
                          longitude: from.longitude + (to.longitude - from.longitude) * fraction), progress < 1)
  }
}

private final class DisplayLinkTarget: NSObject {
  weak var host: NativeRouteMapHostView?
  @objc func tick(_ link: CADisplayLink) { host?.advanceVehicles(at: link.timestamp) }
}

public final class NativeRouteMapHostView: ExpoView, NMFMapViewLoadDelegate,
  NMFMapViewTouchDelegate, NMFMapViewCameraDelegate, NMFAuthManagerDelegate, CLLocationManagerDelegate {
  let onMapEvent = EventDispatcher()
  private let mapContainer = UIView()
  private let gate = WebOverlayGate()
  private let mapView = RouteNaverMapView(frame: .zero)
  private let locationManager = CLLocationManager()
  private var sessionId: String?
  private var lastRevision = -1
  private var scene: MapScene?
  private var sceneState: MapSceneState?
  private var mapLayout: MapLayout?
  private var stopMarkers: [String: NMFMarker] = [:]
  private var stopLabels: [String: NMFMarker] = [:]
  private var vehicleAppearanceKeys: [String: String] = [:]
  private var vehicleMarkers: [String: NMFMarker] = [:]
  private var routeLines: [(String, NMFPath)] = []
  private var imageCache: [String: NMFOverlayImage] = [:]
  private var motions: [String: RunningMotion] = [:]
  private var displayLink: CADisplayLink?
  private let displayLinkTarget = DisplayLinkTarget()
  private var initializationTimer: Timer?
  private var locationTimer: Timer?
  private var locationRequestId: Int?
  private var locationSessionId: String?
  private var initialFitPending = false
  private var initialFitScheduled = false
  private var pendingCameraCommand: CameraCommand?
  private var vehicleFocusKey: String?
  private var vehicleFocusRevision = 0
  private var vehicleFocusInFlight = false
  private var readySent = false
  private var failed = false
  #if DEBUG
  private var previousCameraTarget: (latitude: Double, longitude: Double)?
  private var lastCameraChangeReason = NMFMapChangedByDeveloper
  private var gestureMoveCount = 0
  private let uiTestingEnabled = ProcessInfo.processInfo.arguments.contains("--native-map-ui-testing")
  #endif

  public required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    backgroundColor = UIColor(red: 0.96, green: 0.95, blue: 0.93, alpha: 1)
    gate.backgroundColor = .clear
    gate.isOpaque = false
    mapContainer.clipsToBounds = true
    mapContainer.isHidden = true
    mapView.accessibilityIdentifier = "salmon-native-map"
    gate.accessibilityIdentifier = "salmon-web-overlay"
    mapView.isTiltGestureEnabled = false
    mapView.isRotateGestureEnabled = false
    mapView.isScrollGestureEnabled = true
    mapView.isZoomGestureEnabled = true
    mapView.addLoadDelegate(delegate: self)
    mapView.addCameraDelegate(delegate: self)
    mapView.touchDelegate = self
    mapView.locationOverlay.icon = NMFOverlayImage(image: MapMarkerImages.location())
    mapView.locationOverlay.iconWidth = 50
    mapView.locationOverlay.iconHeight = 50
    mapView.locationOverlay.subIcon = nil
    mapView.locationOverlay.circleRadius = 0
    NMFAuthManager.shared().delegate = self
    locationManager.delegate = self
    locationManager.desiredAccuracy = kCLLocationAccuracyBest
    displayLinkTarget.host = self
    addSubview(mapContainer)
    mapContainer.addSubview(mapView)
    addSubview(gate)
    NotificationCenter.default.addObserver(self, selector: #selector(enteredBackground),
                                           name: UIApplication.didEnterBackgroundNotification, object: nil)
    NotificationCenter.default.addObserver(self, selector: #selector(becameActive),
                                           name: UIApplication.didBecomeActiveNotification, object: nil)
    NotificationCenter.default.addObserver(self, selector: #selector(sceneBackgrounded(_:)),
                                           name: UIScene.didEnterBackgroundNotification, object: nil)
    NotificationCenter.default.addObserver(self, selector: #selector(sceneActivated(_:)),
                                           name: UIScene.didActivateNotification, object: nil)
  }

  // Fabric must mount the existing RNCWebView inside the gate, not beside it.
  public override func mountChildComponentView(_ childComponentView: UIView, index: Int) {
    gate.insertSubview(childComponentView, at: index)
  }

  public override func unmountChildComponentView(_ childComponentView: UIView, index: Int) {
    childComponentView.removeFromSuperview()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    gate.frame = bounds
    applyLayout()
  }

  public func handleMessage(_ message: String) {
    guard let bytes = message.data(using: .utf8), bytes.count <= 4_000_000 else { return }
    do {
      let envelope = try JSONDecoder().decode(MapEnvelope.self, from: bytes)
      guard envelope.v == 1, !envelope.sessionId.isEmpty, envelope.sessionId.count <= 128,
            envelope.revision >= 0,
            let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
            let payload = object["payload"] else { return }
      let payloadBytes = try JSONSerialization.data(withJSONObject: payload)
      if envelope.type == "mount" {
        if sessionId == envelope.sessionId && envelope.revision <= lastRevision { return }
        reset()
        sessionId = envelope.sessionId
        lastRevision = envelope.revision
        let nextScene = try JSONDecoder().decode(MapScene.self, from: payloadBytes)
        mount(nextScene)
        return
      }
      guard sessionId == envelope.sessionId, envelope.revision > lastRevision else { return }
      lastRevision = envelope.revision
      switch envelope.type {
      case "updateScene":
        updateScene(try JSONDecoder().decode(MapSceneState.self, from: payloadBytes))
      case "layout":
        let layout = try JSONDecoder().decode(MapLayout.self, from: payloadBytes)
        guard layout.valid else { fail("지도 표시 영역을 확인하지 못했어요"); return }
        mapLayout = layout
        applyLayout()
        emit("ack", ["revision": envelope.revision])
      case "motion":
        updateMotion(try JSONDecoder().decode(MotionPayload.self, from: payloadBytes))
      case "camera":
        moveCamera(try JSONDecoder().decode(CameraCommand.self, from: payloadBytes))
      case "location":
        requestLocation(try JSONDecoder().decode(LocationRequest.self, from: payloadBytes))
      case "unmount": reset()
      default: break
      }
    } catch {
      fail("지도 요청을 읽지 못했어요")
    }
  }

  public func reset() {
    cancelVehicleFocus()
    initializationTimer?.invalidate()
    initializationTimer = nil
    finishLocation(error: "unavailable")
    stopDisplayLink()
    motions.removeAll()
    stopMarkers.values.forEach { $0.mapView = nil }
    stopLabels.values.forEach { $0.mapView = nil }
    vehicleMarkers.values.forEach { $0.mapView = nil }
    routeLines.forEach { $0.1.mapView = nil }
    stopMarkers.removeAll()
    stopLabels.removeAll()
    vehicleAppearanceKeys.removeAll()
    vehicleMarkers.removeAll()
    routeLines.removeAll()
    imageCache.removeAll()
    mapView.locationOverlay.hidden = true
    scene = nil
    sceneState = nil
    mapLayout = nil
    sessionId = nil
    lastRevision = -1
    readySent = false
    failed = false
    #if DEBUG
    previousCameraTarget = nil
    lastCameraChangeReason = NMFMapChangedByDeveloper
    gestureMoveCount = 0
    #endif
    initialFitPending = false
    initialFitScheduled = false
    pendingCameraCommand = nil
    vehicleFocusInFlight = false
    gate.captureAll = true
    gate.webRects = []
    mapContainer.isHidden = true
    mapView.accessibilityElementsHidden = true
    updateVerificationSnapshot()
  }

  private func mount(_ nextScene: MapScene) {
    guard !nextScene.stops.isEmpty || !nextScene.fullPath.isEmpty || nextScene.paths.contains(where: { !$0.coordinates.isEmpty }),
          nextScene.stops.allSatisfy({ $0.coordinate.valid }),
          nextScene.paths.allSatisfy({ $0.coordinates.allSatisfy(\.valid) }),
          nextScene.fullPath.allSatisfy(\.valid) else {
      fail("지도 좌표를 확인하지 못했어요")
      return
    }
    scene = nextScene
    let initialStop = nextScene.stops.first { $0.selection == nextScene.selectedStop }
    initialFitPending = initialStop == nil
    if let initialStop {
      let camera = NMFCameraUpdate(scrollTo: initialStop.coordinate.naver, zoomTo: 13)
      camera.animation = .none
      mapView.moveCamera(camera)
    }
    #if DEBUG
    NSLog("[SalmonNativeMap] mount stops=%ld paths=%ld vehicles=%ld", nextScene.stops.count,
          nextScene.paths.count, nextScene.vehicles.count)
    #endif
    mapContainer.isHidden = false
    mapView.accessibilityElementsHidden = false
    for path in nextScene.paths where path.coordinates.count > 1 {
      let line = NMFPath()
      line.path = NMGLineString(points: path.coordinates.map(\.naver))
      line.width = 6
      line.outlineWidth = 0
      line.patternInterval = 100
      line.mapView = mapView
      routeLines.append((path.direction, line))
    }
    let missingDirections = Set(nextScene.stops.map(\.direction)).subtracting(nextScene.paths.map(\.direction))
    if !missingDirections.isEmpty, nextScene.fullPath.count > 1 {
      let line = NMFPath()
      line.path = NMGLineString(points: nextScene.fullPath.map(\.naver))
      line.width = 6
      line.outlineWidth = 0
      line.color = MapMarkerImages.mutedColor.withAlphaComponent(0.95)
      line.mapView = mapView
      line.zIndex = 0
      routeLines.append(("", line))
    }
    for stop in nextScene.stops {
      let marker = NMFMarker(position: stop.coordinate.naver)
      marker.anchor = CGPoint(x: 0.5, y: 0.5)
      marker.isForceShowIcon = true
      marker.minZoom = nextScene.paths.contains { $0.direction == stop.direction } ? 14 : 0
      let label = NMFMarker(position: stop.coordinate.naver)
      label.isForceShowIcon = true
      label.minZoom = 14
      label.mapView = mapView
      stopLabels[stop.key] = label
      marker.touchHandler = { [weak self] _ in
        self?.emit("stopPress", ["selection": stop.selection.json])
        return true
      }
      marker.mapView = mapView
      stopMarkers[stop.key] = marker
    }
    updateScene(nextScene.state)
    applyLayout()
    if mapView.loaded { notifyReady() }
    let expectedSession = sessionId
    initializationTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
      guard let self, self.sessionId == expectedSession, !self.readySent else { return }
      self.fail("지도를 불러오지 못했어요")
    }
  }

  private func updateScene(_ state: MapSceneState) {
    guard let scene else { return }
    sceneState = state
    for stop in scene.stops {
      guard let marker = stopMarkers[stop.key] else { continue }
      let selected = state.selectedStop == stop.selection
      let active = state.activeDirection == stop.direction
      marker.iconImage = overlayImage("stop:\(selected):\(active):\(stop.boardingAllowed)") {
        MapMarkerImages.stop(selected: selected, active: active, boardingAllowed: stop.boardingAllowed)
      }
      marker.width = selected ? 44 : 36
      marker.height = marker.width
      marker.zIndex = selected ? 15 : active ? 6 : 5
    }
    refreshRouteStyles()
    let keys = Set(state.vehicles.map(\.key))
    if let key = vehicleFocusKey, !keys.contains(key) { cancelVehicleFocus() }
    for key in Array(vehicleMarkers.keys) where !keys.contains(key) {
      vehicleMarkers.removeValue(forKey: key)?.mapView = nil
      motions.removeValue(forKey: key)
      vehicleAppearanceKeys.removeValue(forKey: key)
    }
    for vehicle in state.vehicles where vehicle.coordinate.valid {
      let marker = vehicleMarkers[vehicle.key] ?? NMFMarker(position: vehicle.coordinate.naver)
      if motions[vehicle.key] == nil { marker.position = vehicle.coordinate.naver }
      marker.anchor = CGPoint(x: 0.5, y: 1)
      marker.isForceShowIcon = true
      marker.zIndex = state.selectedVehicleKey == vehicle.key ? 30 : 20
      marker.touchHandler = { [weak self] _ in
        self?.emit("vehiclePress", ["selection": vehicle.selection.json, "key": vehicle.key])
        return true
      }
      marker.mapView = mapView
      vehicleMarkers[vehicle.key] = marker
    }
    refreshVehicleImages()
    refreshStopAnchors()
  }

  private func refreshStopAnchors() {
    guard readySent, let scene, let state = sceneState, mapView.bounds.width > 1 else { return }
    let projected = scene.stops.compactMap { stop -> (stop: MapStop, point: CGPoint, size: CGFloat)? in
      let point = mapView.projection.point(from: stop.coordinate.naver)
      guard point.x.isFinite, point.y.isFinite else { return nil }
      return (stop, point, state.selectedStop == stop.selection ? 32 : 24)
    }
    var offsets: [String: CGFloat] = [:]
    for up in projected where up.stop.direction == "UP" {
      for down in projected where down.stop.direction == "DOWN" {
        let separation = (up.size + down.size)/2 + 4
        let difference = up.point.x - down.point.x
        guard abs(difference) < separation, abs(up.point.y - down.point.y) < separation else { continue }
        let offset = (separation - difference)/2
        offsets[up.stop.key] = max(offsets[up.stop.key] ?? 0, offset)
        offsets[down.stop.key] = min(offsets[down.stop.key] ?? 0, -offset)
      }
    }
    for item in projected {
      guard let marker = stopMarkers[item.stop.key], let label = stopLabels[item.stop.key] else { continue }
      let offset = offsets[item.stop.key] ?? 0
      // Offset presentation coordinates so SDK anchors stay within their documented 0...1 range.
      let coordinate = mapView.projection.latlng(from: CGPoint(x: item.point.x + offset, y: item.point.y))
      marker.position = coordinate
      let selected = state.selectedStop == item.stop.selection
      let above = offset != 0 && item.stop.direction != state.activeDirection
      let icon = overlayImage("label:\(item.stop.key):\(selected):\(above)") {
        MapMarkerImages.stopName(item.stop.name, selected: selected, above: above)
      }
      if label.iconImage !== icon { label.iconImage = icon }
      label.position = coordinate
      label.anchor = CGPoint(x: 0.5, y: above ? 1 : 0)
      label.zIndex = marker.zIndex
    }
  }

  private func refreshVehicleImages() {
    guard let state = sceneState else { return }
    let showCount = mapView.cameraPosition.zoom >= 14
    var counts: [String: Int] = [:]
    for vehicle in state.vehicles {
      guard let marker = vehicleMarkers[vehicle.key] else { continue }
      let position = "\(marker.position.lat):\(marker.position.lng)"
      let index = counts[position, default: 0]
      counts[position] = index + 1
      let selected = state.selectedVehicleKey == vehicle.key
      let imageKey = "vehicle:\(vehicle.caption):\(showCount):\(showCount ? index : 0)"
      let appearanceKey = "\(imageKey):\(selected)"
      guard vehicleAppearanceKeys[vehicle.key] != appearanceKey else { continue }
      let icon = overlayImage(imageKey) {
        MapMarkerImages.vehicle(caption: vehicle.caption, showCount: showCount, stackIndex: index)
      }
      marker.iconImage = icon
      let scale: CGFloat = selected ? 1.5 : 1
      marker.width = icon.image.size.width * scale
      marker.height = icon.image.size.height * scale
      vehicleAppearanceKeys[vehicle.key] = appearanceKey
    }
  }

  private func refreshRouteStyles() {
    guard let state = sceneState else { return }
    let showBothDirections = mapView.cameraPosition.zoom >= 14
    for (direction, line) in routeLines {
      let active = direction == state.activeDirection
      line.color = (active ? MapMarkerImages.routeColor : MapMarkerImages.mutedColor.withAlphaComponent(0.95))
      line.passedColor = line.color
      line.zIndex = active ? 2 : 1
      // Each directional path is already ordered in its own operating direction.
      line.patternIcon = !direction.isEmpty && (active || showBothDirections) ? routeArrowImage() : nil
    }
  }

  private func applyLayout() {
    guard let layout = mapLayout, layout.valid, bounds.width > 0, bounds.height > 0 else { return }
    let rect = layout.nativeRect(layout.mapRect, in: bounds).intersection(bounds)
    gate.mapRect = rect
    gate.webRects = layout.webRects.map { layout.nativeRect($0, in: bounds) }
    // During DOM geometry transitions, a new touch belongs to WebKit until the final layout is applied.
    gate.captureAll = layout.captureAll || layout.transitioning || !readySent || failed
    mapContainer.frame = rect.isNull ? .zero : rect
    let bufferHeight = max(bounds.height, rect.height)
    let crop = max(0, (bufferHeight - rect.height) / 2)
    mapView.frame = CGRect(x: 0, y: -crop, width: rect.width, height: bufferHeight)
    mapView.logoAlign = .leftBottom
    mapView.logoMargin = UIEdgeInsets(top: 0, left: 12, bottom: crop + 12, right: 0)
    mapView.accessibilityElementsHidden = layout.captureAll || rect.height < 1
    scheduleInitialFit()
    updateVerificationSnapshot()
  }

  private func scheduleInitialFit() {
    guard readySent, !failed, initialFitPending || pendingCameraCommand != nil, !initialFitScheduled,
          mapContainer.bounds.width > 1, mapContainer.bounds.height > 1, window != nil else { return }
    initialFitScheduled = true
    let expectedSession = sessionId
    // The SDK can finish its first tile load before its child renderer has laid out at our new frame.
    // Defer camera fitting until the map's actual UIKit layout has completed.
    DispatchQueue.main.async { [weak self] in
      guard let self, self.sessionId == expectedSession else { return }
      self.initialFitScheduled = false
      guard self.readySent, !self.failed else { return }
      self.mapContainer.layoutIfNeeded()
      self.mapView.setNeedsLayout()
      self.mapView.layoutIfNeeded()
      if let command = self.pendingCameraCommand {
        self.pendingCameraCommand = nil
        self.moveCamera(command)
      } else if self.initialFitPending {
        self.initialFitPending = false
        self.fitRoute()
      }
    }
  }

  private func fitRoute() {
    guard let scene else { return }
    let route = scene.fullPath.isEmpty ? scene.paths.flatMap(\.coordinates) : scene.fullPath
    let coordinates = route.isEmpty ? scene.stops.map(\.coordinate) : route
    guard let first = coordinates.first else { return }
    let south = coordinates.map(\.latitude).min() ?? first.latitude
    let north = coordinates.map(\.latitude).max() ?? first.latitude
    let west = coordinates.map(\.longitude).min() ?? first.longitude
    let east = coordinates.map(\.longitude).max() ?? first.longitude
    if south == north && west == east {
      mapView.moveCamera(NMFCameraUpdate(scrollTo: first.naver, zoomTo: 13))
      return
    }
    let visibleHeight = max(1, mapContainer.bounds.height)
    let crop = max(0, (mapView.bounds.height - visibleHeight) / 2)
    let area = NMGLatLngBounds(southWest: NMGLatLng(lat: south, lng: west),
                              northEast: NMGLatLng(lat: north, lng: east))
    mapView.moveCamera(NMFCameraUpdate(fit: area,
      paddingInsets: UIEdgeInsets(top: crop + 28, left: 12, bottom: crop + 28, right: 12)))
    if mapView.cameraPosition.zoom > 13 {
      mapView.moveCamera(NMFCameraUpdate(scrollTo: mapView.cameraPosition.target, zoomTo: 13))
    }
    #if DEBUG
    NSLog("[SalmonNativeMap] initial fit applied stops=%ld vehicles=%ld", stopMarkers.count, vehicleMarkers.count)
    #endif
  }

  private func moveCamera(_ command: CameraCommand) {
    cancelVehicleFocus()
    if command.command == "vehicle", command.key == nil {
      if pendingCameraCommand?.command == "vehicle" {
        pendingCameraCommand = nil
        if !readySent { initialFitPending = true }
      }
      return
    }
    var coordinate: NMGLatLng?
    switch command.command {
    case "stop":
      coordinate = scene?.stops.first(where: { $0.selection == command.selection })?.coordinate.naver
    case "vehicle":
      if let key = command.key { coordinate = vehicleMarkers[key]?.position }
    case "location":
      if let location = command.coordinate, location.valid {
        coordinate = location.naver
        mapView.locationOverlay.location = location.naver
        mapView.locationOverlay.hidden = false
      }
    default: break
    }
    guard let coordinate else { return }
    if !readySent || mapLayout == nil || mapView.bounds.width <= 1 {
      pendingCameraCommand = command
      initialFitPending = false
      return
    }
    initialFitPending = false
    let minimumZoom = command.command == "vehicle" ? 16.0 : 15.0
    let update = NMFCameraUpdate(scrollTo: coordinate, zoomTo: max(minimumZoom, mapView.cameraPosition.zoom))
    update.animation = UIAccessibility.isReduceMotionEnabled ? .none : .easeIn
    update.animationDuration = 0.35
    vehicleFocusKey = command.command == "vehicle" ? command.key : nil
    vehicleFocusInFlight = command.command == "vehicle"
    let revision = vehicleFocusRevision
    mapView.moveCamera(update) { [weak self] cancelled in
      guard let self, self.vehicleFocusRevision == revision else { return }
      self.vehicleFocusInFlight = false
      if cancelled { self.cancelVehicleFocus() }
      else { self.followVehicle() }
    }
  }

  private func cancelVehicleFocus() {
    vehicleFocusRevision += 1
    vehicleFocusKey = nil
    let inFlight = vehicleFocusInFlight
    vehicleFocusInFlight = false
    if inFlight { mapView.cancelTransitions() }
  }

  private func followVehicle() {
    guard !vehicleFocusInFlight, let key = vehicleFocusKey,
          let marker = vehicleMarkers[key], readySent, !failed else { return }
    let center = mapView.cameraPosition.target
    let coordinate = marker.position
    guard center.lat != coordinate.lat || center.lng != coordinate.lng else { return }
    // CADisplayLink가 보간한 핀 좌표를 그대로 따라가며 SDK 애니메이션을 겹치지 않는다.
    let update = NMFCameraUpdate(scrollTo: coordinate)
    update.animation = .none
    mapView.moveCamera(update)
  }

  private func updateMotion(_ payload: MotionPayload) {
    let now = CACurrentMediaTime()
    for plan in payload.vehicles {
      guard !plan.path.isEmpty, plan.durationMs.isFinite, plan.durationMs >= 0,
            plan.path.allSatisfy({ $0.coordinate.valid && $0.progress.isFinite && $0.progress >= 0 && $0.progress <= 1 }),
            zip(plan.path, plan.path.dropFirst()).allSatisfy({ $0.0.progress <= $0.1.progress }) else { continue }
      if motions[plan.key]?.plan.signature == plan.signature { continue }
      motions[plan.key] = RunningMotion(plan: plan, startedAt: now)
    }
    advanceVehicles(at: now)
    startDisplayLinkIfNeeded(at: now)
  }

  private func startDisplayLinkIfNeeded(at now: CFTimeInterval) {
    guard displayLink == nil, !failed, motions.values.contains(where: { $0.sample(at: now).1 }) else { return }
    let link = CADisplayLink(target: displayLinkTarget, selector: #selector(DisplayLinkTarget.tick(_:)))
    link.add(to: .main, forMode: .common)
    displayLink = link
  }

  fileprivate func advanceVehicles(at now: CFTimeInterval) {
    var active = false
    for (key, motion) in motions {
      let (coordinate, moving) = motion.sample(at: now)
      if let coordinate { vehicleMarkers[key]?.position = coordinate.naver }
      active = active || moving
    }
    followVehicle()
    refreshVehicleImages()
    if !active { stopDisplayLink() }
  }

  private func stopDisplayLink() { displayLink?.invalidate(); displayLink = nil }

  @objc private func enteredBackground() {
    stopDisplayLink()
    finishLocation(error: "unavailable")
  }

  @objc private func becameActive() {
    let now = CACurrentMediaTime()
    advanceVehicles(at: now)
    startDisplayLinkIfNeeded(at: now)
  }

  @objc private func sceneBackgrounded(_ notification: Notification) {
    guard let scene = notification.object as? UIScene, scene === window?.windowScene else { return }
    enteredBackground()
  }

  @objc private func sceneActivated(_ notification: Notification) {
    guard let scene = notification.object as? UIScene, scene === window?.windowScene else { return }
    becameActive()
  }

  private func notifyReady() {
    guard sessionId != nil, !readySent, !failed else { return }
    readySent = true
    initializationTimer?.invalidate()
    initializationTimer = nil
    // The first scene may arrive while the SDK's renderer still has a zero-sized frame.
    // Bind every overlay to the loaded renderer before revealing the web map placeholder.
    for marker in stopMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for marker in stopLabels.values { marker.mapView = nil; marker.mapView = mapView }
    for marker in vehicleMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for (_, line) in routeLines { line.mapView = nil; line.mapView = mapView }
    applyLayout()
    refreshStopAnchors()
    refreshVehicleImages()
    emit("ready", [:])
    updateVerificationSnapshot()
    #if DEBUG
    NSLog("[SalmonNativeMap] native SDK ready")
    #endif
  }

  public func mapViewDidFinishLoadingMap(_ mapView: NMFMapView) { notifyReady() }

  public func mapView(_ mapView: NMFMapView, cameraWillChangeByReason reason: Int, animated: Bool) {
    if reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl { cancelVehicleFocus() }
    #if DEBUG
    lastCameraChangeReason = reason
    NSLog("[SalmonNativeMap] camera willChange reason=%@ animated=%@",
          reason == NMFMapChangedByGesture ? "gesture" : "programmatic", animated ? "true" : "false")
    #endif
  }

  public func mapView(_ mapView: NMFMapView, cameraIsChangingByReason reason: Int) {
    if reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl { cancelVehicleFocus() }
    refreshStopAnchors()
    refreshVehicleImages()
    refreshRouteStyles()
    #if DEBUG
    lastCameraChangeReason = reason
    #endif
  }

  public func mapViewCameraIdle(_ mapView: NMFMapView) {
    refreshStopAnchors()
    refreshVehicleImages()
    refreshRouteStyles()
    #if DEBUG
    let target = mapView.cameraPosition.target
    let moved = previousCameraTarget.map {
      abs(target.lat - $0.latitude) > 0.0000001 || abs(target.lng - $0.longitude) > 0.0000001
    } ?? false
    previousCameraTarget = (target.lat, target.lng)
    if moved && lastCameraChangeReason == NMFMapChangedByGesture { gestureMoveCount += 1 }
    let coordinates = (scene?.stops.map { $0.coordinate.naver }) ?? []
    let targetInRouteBounds = coordinates.isEmpty ? false
      : NMGLatLngBounds(latLngs: coordinates).hasPoint(mapView.cameraPosition.target)
    let attachedStops = stopMarkers.values.filter { $0.mapView === mapView }.count
    let attachedVehicles = vehicleMarkers.values.filter { $0.mapView === mapView }.count
    NSLog("[SalmonNativeMap] camera idle zoom=%.2f moved=%@ reason=%@ routeCenter=%@ stops=%ld vehicles=%ld",
          mapView.cameraPosition.zoom, moved ? "true" : "false",
          lastCameraChangeReason == NMFMapChangedByGesture ? "gesture" : "programmatic",
          targetInRouteBounds ? "inside" : "outside", attachedStops, attachedVehicles)
    updateVerificationSnapshot()
    #endif
  }

  public func mapView(_ mapView: NMFMapView, didTapMap latlng: NMGLatLng, point: CGPoint) {
    emit("mapPress", [:])
  }

  public func authorized(_ state: NMFAuthState, error: Error?) {
    if error != nil { fail("지도 설정을 확인하지 못했어요") }
  }

  private func fail(_ message: String) {
    cancelVehicleFocus()
    failed = true
    readySent = false
    gate.captureAll = true
    initializationTimer?.invalidate()
    stopDisplayLink()
    emit("error", ["message": message])
    updateVerificationSnapshot()
  }

  private func updateVerificationSnapshot() {
    #if DEBUG
    guard uiTestingEnabled else { return }
    let snapshot: [String: Any] = [
      "ready": readySent && !failed && !initialFitPending && !initialFitScheduled,
      "zoom": mapView.cameraPosition.zoom,
      "gestureMoves": gestureMoveCount,
      "visibleMapHeight": mapContainer.bounds.height,
      "captureAll": gate.captureAll,
      "stops": stopMarkers.values.filter { $0.mapView === mapView }.count,
      "vehicles": vehicleMarkers.values.filter { $0.mapView === mapView }.count,
    ]
    guard let data = try? JSONSerialization.data(withJSONObject: snapshot),
          let value = String(data: data, encoding: .utf8) else { return }
    mapView.verificationValue = value
    #endif
  }

  private func emit(_ type: String, _ payload: [String: Any]) {
    guard let sessionId else { return }
    let event: [String: Any] = ["v": 1, "sessionId": sessionId, "revision": max(0, lastRevision),
                                "type": type, "payload": payload]
    guard let data = try? JSONSerialization.data(withJSONObject: event),
          let message = String(data: data, encoding: .utf8) else { return }
    onMapEvent(["message": message])
  }

  private func overlayImage(_ key: String, draw: () -> UIImage) -> NMFOverlayImage {
    if let cached = imageCache[key] { return cached }
    let image = NMFOverlayImage(image: draw())
    imageCache[key] = image
    return image
  }

  private func routeArrowImage() -> NMFOverlayImage {
    overlayImage("route-arrow") { MapMarkerImages.routeArrow() }
  }

  private func requestLocation(_ request: LocationRequest) {
    finishLocation(error: "unavailable")
    locationRequestId = request.requestId
    locationSessionId = sessionId
    switch locationManager.authorizationStatus {
    case .notDetermined: locationManager.requestWhenInUseAuthorization()
    case .authorizedAlways, .authorizedWhenInUse: startLocationRequest(locationManager)
    default: finishLocation(error: "denied"); return
    }
  }

  private func startLocationRequest(_ manager: CLLocationManager) {
    manager.requestLocation()
    locationTimer?.invalidate()
    locationTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
      self?.finishLocation(error: "timeout")
    }
  }

  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    guard locationRequestId != nil else { return }
    switch manager.authorizationStatus {
    case .authorizedAlways, .authorizedWhenInUse: startLocationRequest(manager)
    case .denied, .restricted: finishLocation(error: "denied")
    default: break
    }
  }

  public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let location = locations.last else { finishLocation(error: "unavailable"); return }
    finishLocation(coordinate: MapCoordinate(latitude: location.coordinate.latitude,
                                            longitude: location.coordinate.longitude))
  }

  public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    finishLocation(error: (error as? CLError)?.code == .denied ? "denied" : "unavailable")
  }

  private func finishLocation(coordinate: MapCoordinate? = nil, error: String? = nil) {
    locationTimer?.invalidate()
    locationTimer = nil
    locationManager.stopUpdatingLocation()
    if let requestId = locationRequestId, locationSessionId == sessionId {
      var payload: [String: Any] = ["requestId": requestId]
      if let coordinate { payload["coordinate"] = coordinate.json }
      if let error { payload["error"] = error }
      emit("locationResult", payload)
    }
    locationRequestId = nil
    locationSessionId = nil
  }

  deinit {
    displayLink?.invalidate()
    initializationTimer?.invalidate()
    locationTimer?.invalidate()
    NotificationCenter.default.removeObserver(self)
    mapView.removeLoadDelegate(delegate: self)
    mapView.removeCameraDelegate(delegate: self)
  }
}
