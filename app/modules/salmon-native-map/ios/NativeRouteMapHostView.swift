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
        let nextScene = try JSONDecoder().decode(MapScene.self, from: payloadBytes)
        if sessionId == envelope.sessionId && envelope.revision <= lastRevision { return }
        reset()
        sessionId = envelope.sessionId
        lastRevision = envelope.revision
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
    initializationTimer?.invalidate()
    initializationTimer = nil
    finishLocation(error: "unavailable")
    stopDisplayLink()
    motions.removeAll()
    stopMarkers.values.forEach { $0.mapView = nil }
    vehicleMarkers.values.forEach { $0.mapView = nil }
    routeLines.forEach { $0.1.mapView = nil }
    stopMarkers.removeAll()
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
    initialFitPending = true
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
      line.width = 4
      line.outlineWidth = 0
      line.color = UIColor.systemGray
      line.mapView = mapView
      line.zIndex = 0
      routeLines.append(("", line))
    }
    for stop in nextScene.stops {
      let marker = NMFMarker(position: stop.coordinate.naver)
      marker.anchor = CGPoint(x: 0.5, y: 0.5)
      marker.captionText = stop.name
      marker.captionTextSize = 12
      marker.captionMinZoom = 12
      marker.captionColor = .white
      marker.captionHaloColor = .black
      marker.isHideCollidedCaptions = true
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
      marker.iconImage = stopImage(selected: selected, active: active)
      marker.width = selected ? 32 : 24
      marker.height = selected ? 32 : 24
      marker.zIndex = selected ? 20 : active ? 10 : 0
      marker.captionText = active ? stop.name : ""
      marker.isForceShowCaption = selected
      marker.captionMinZoom = selected ? 0 : 12
    }
    refreshRouteStyles()
    let keys = Set(state.vehicles.map(\.key))
    for key in Array(vehicleMarkers.keys) where !keys.contains(key) {
      vehicleMarkers.removeValue(forKey: key)?.mapView = nil
      motions.removeValue(forKey: key)
    }
    for vehicle in state.vehicles where vehicle.coordinate.valid {
      let marker = vehicleMarkers[vehicle.key] ?? NMFMarker(position: vehicle.coordinate.naver)
      if motions[vehicle.key] == nil { marker.position = vehicle.coordinate.naver }
      marker.iconImage = vehicleImage(selected: state.selectedVehicleKey == vehicle.key)
      marker.width = state.selectedVehicleKey == vehicle.key ? 40 : 34
      marker.height = state.selectedVehicleKey == vehicle.key ? 48 : 40
      marker.anchor = CGPoint(x: 0.5, y: 0.9)
      marker.captionText = vehicle.caption
      marker.captionColor = salmon
      marker.captionTextSize = 12
      marker.captionHaloColor = .white
      marker.isForceShowIcon = true
      marker.isForceShowCaption = true
      marker.zIndex = 30
      marker.touchHandler = { [weak self] _ in
        self?.emit("vehiclePress", ["selection": vehicle.selection.json, "key": vehicle.key])
        return true
      }
      marker.mapView = mapView
      vehicleMarkers[vehicle.key] = marker
    }
    refreshStopAnchors()
  }

  private func refreshStopAnchors() {
    guard readySent, let scene, mapView.bounds.width > 1 else { return }
    let projected = scene.stops.compactMap { stop -> (stop: MapStop, point: CGPoint, size: CGFloat)? in
      guard let marker = stopMarkers[stop.key] else { return nil }
      let point = mapView.projection.point(from: stop.coordinate.naver)
      guard point.x.isFinite, point.y.isFinite else { return nil }
      return (stop, point, marker.width)
    }
    var offsets: [String: CGFloat] = [:]
    for up in projected where up.stop.direction == "UP" {
      for down in projected where down.stop.direction == "DOWN" {
        let separation = (up.size + down.size) / 2 + 4
        let difference = up.point.x - down.point.x
        guard abs(difference) < separation, abs(up.point.y - down.point.y) < separation else { continue }
        // Keep the geographic point fixed. Choose the nearer screen side without requiring an
        // anchor outside the SDK's documented 0...1 range; identical points put UP on the right.
        let offset = (separation - abs(difference)) / 2
        let sign: CGFloat = difference >= 0 ? 1 : -1
        let upOffset = sign * min(offset, up.size / 2)
        let downOffset = -sign * min(offset, down.size / 2)
        if abs(upOffset) > abs(offsets[up.stop.key] ?? 0) { offsets[up.stop.key] = upOffset }
        if abs(downOffset) > abs(offsets[down.stop.key] ?? 0) { offsets[down.stop.key] = downOffset }
      }
    }
    for item in projected {
      let offset = offsets[item.stop.key] ?? 0
      stopMarkers[item.stop.key]?.anchor = CGPoint(x: 0.5 - offset / item.size, y: 0.5)
    }
  }

  private func refreshRouteStyles() {
    guard let state = sceneState else { return }
    let showBothDirections = mapView.cameraPosition.zoom >= 14
    for (direction, line) in routeLines {
      let active = direction == state.activeDirection
      line.color = active ? salmon : UIColor.systemGray3
      line.passedColor = line.color
      line.zIndex = direction.isEmpty ? 0 : active ? 2 : 1
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
    let coordinates = scene.paths.flatMap(\.coordinates) + scene.fullPath + scene.stops.map(\.coordinate)
    guard let first = coordinates.first else { return }
    let south = coordinates.map(\.latitude).min() ?? first.latitude
    let north = coordinates.map(\.latitude).max() ?? first.latitude
    let west = coordinates.map(\.longitude).min() ?? first.longitude
    let east = coordinates.map(\.longitude).max() ?? first.longitude
    if south == north && west == east {
      mapView.moveCamera(NMFCameraUpdate(scrollTo: first.naver, zoomTo: 14))
      return
    }
    let visibleHeight = max(1, mapContainer.bounds.height)
    let crop = max(0, (mapView.bounds.height - visibleHeight) / 2)
    let area = NMGLatLngBounds(southWest: NMGLatLng(lat: south, lng: west),
                              northEast: NMGLatLng(lat: north, lng: east))
    mapView.moveCamera(NMFCameraUpdate(fit: area,
      paddingInsets: UIEdgeInsets(top: crop + 36, left: 36, bottom: crop + 36, right: 36)))
    #if DEBUG
    NSLog("[SalmonNativeMap] initial fit applied stops=%ld vehicles=%ld", stopMarkers.count, vehicleMarkers.count)
    #endif
  }

  private func moveCamera(_ command: CameraCommand) {
    if command.command == "vehicle", command.key == nil {
      if vehicleFocusInFlight {
        mapView.cancelTransitions()
        vehicleFocusInFlight = false
      }
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
    vehicleFocusInFlight = command.command == "vehicle"
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
    for marker in vehicleMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for (_, line) in routeLines { line.mapView = nil; line.mapView = mapView }
    applyLayout()
    emit("ready", [:])
    updateVerificationSnapshot()
    #if DEBUG
    NSLog("[SalmonNativeMap] native SDK ready")
    #endif
  }

  public func mapViewDidFinishLoadingMap(_ mapView: NMFMapView) { notifyReady() }

  public func mapView(_ mapView: NMFMapView, cameraWillChangeByReason reason: Int, animated: Bool) {
    #if DEBUG
    lastCameraChangeReason = reason
    NSLog("[SalmonNativeMap] camera willChange reason=%@ animated=%@",
          reason == NMFMapChangedByGesture ? "gesture" : "programmatic", animated ? "true" : "false")
    #endif
  }

  public func mapView(_ mapView: NMFMapView, cameraIsChangingByReason reason: Int) {
    #if DEBUG
    lastCameraChangeReason = reason
    #endif
  }

  public func mapViewCameraIdle(_ mapView: NMFMapView) {
    vehicleFocusInFlight = false
    refreshStopAnchors()
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

  private var salmon: UIColor { UIColor(red: 0.79, green: 0.35, blue: 0.31, alpha: 1) }

  private func routeArrowImage() -> NMFOverlayImage {
    let key = "route-arrow"
    if let cached = imageCache[key] { return cached }
    let image = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 6)).image { _ in
      let arrow = UIBezierPath()
      arrow.move(to: CGPoint(x: 4, y: 1))
      arrow.addLine(to: CGPoint(x: 8, y: 3))
      arrow.addLine(to: CGPoint(x: 4, y: 5))
      arrow.lineWidth = 1.8
      arrow.lineCapStyle = .round
      arrow.lineJoinStyle = .round
      UIColor.white.setStroke()
      arrow.stroke()
    }
    let overlay = NMFOverlayImage(image: image)
    imageCache[key] = overlay
    return overlay
  }

  private func stopImage(selected: Bool, active: Bool) -> NMFOverlayImage {
    let key = "stop:\(selected):\(active)"
    if let cached = imageCache[key] { return cached }
    let color = active ? salmon : UIColor.systemGray
    let image = UIGraphicsImageRenderer(size: CGSize(width: 32, height: 32)).image { _ in
      let circle = UIBezierPath(ovalIn: CGRect(x: 3, y: 3, width: 26, height: 26))
      (selected ? color : UIColor.white).setFill(); circle.fill()
      color.setStroke(); circle.lineWidth = 3; circle.stroke()
      let symbol = UIImage(systemName: "bus.fill")?.withTintColor(selected ? .white : color, renderingMode: .alwaysOriginal)
      symbol?.draw(in: CGRect(x: 9, y: 8, width: 14, height: 16))
    }
    let overlay = NMFOverlayImage(image: image)
    imageCache[key] = overlay
    return overlay
  }

  private func vehicleImage(selected: Bool) -> NMFOverlayImage {
    let key = "vehicle:\(selected)"
    if let cached = imageCache[key] { return cached }
    let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 48)).image { _ in
      let body = UIBezierPath(roundedRect: CGRect(x: 3, y: 2, width: 34, height: 40), cornerRadius: 9)
      salmon.setFill(); body.fill()
      (selected ? UIColor.black : UIColor.white).setStroke(); body.lineWidth = selected ? 3 : 2; body.stroke()
      UIImage(systemName: "bus.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal)
        .draw(in: CGRect(x: 10, y: 9, width: 20, height: 25))
    }
    let overlay = NMFOverlayImage(image: image)
    imageCache[key] = overlay
    return overlay
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
