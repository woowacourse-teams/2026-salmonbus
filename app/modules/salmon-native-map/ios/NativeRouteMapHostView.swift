import CoreLocation
import ExpoModulesCore
import NMapsMap
import UIKit
import WebKit

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

private final class WebOverlayGate: UIView {
  var captureAll = true
  var mapRect = CGRect.zero
  var webRects: [CGRect] = []
  weak var navigationSnapshot: MapNavigationSnapshot?

  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    guard !isHidden, alpha >= 0.01, isUserInteractionEnabled, bounds.contains(point) else { return nil }
    let web = captureAll || navigationSnapshot?.capturesNavigationEdge(point, in: self) == true
      || !mapRect.contains(point) || webRects.contains { $0.contains(point) }
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
  NMFMapViewTouchDelegate, NMFMapViewCameraDelegate, NMFAuthManagerDelegate, CLLocationManagerDelegate,
  UIGestureRecognizerDelegate {
  let onMapEvent = EventDispatcher()
  private let mapContainer = UIView()
  private let gate = WebOverlayGate()
  private let mapView = RouteNaverMapView(frame: .zero)
  private lazy var rotationGesture = UIRotationGestureRecognizer(target: self, action: #selector(rotateMap(_:)))
  private lazy var navigationSnapshot = MapNavigationSnapshot(mapContainer: mapContainer)
  private let locationManager = CLLocationManager()
  private var sessionId: String?
  private var lastRevision = -1
  private var scene: MapScene?
  private var sceneState: MapSceneState?
  private var homeMounted = false
  private var homeMarkers: [String: NMFMarker] = [:]
  private var homePaths: [MapHomePath] = []
  private var homeLines: [NMFPath] = []
  private var pendingHomeCamera: MapHomeCameraCommand?
  private var homeCameraGesture = false
  private var mapLayout: MapLayout?
  private var stopMarkers: [String: NMFMarker] = [:]
  private var stopLabels: [String: NMFMarker] = [:]
  private var vehicleAppearanceKeys: [String: String] = [:]
  private var vehicleMarkers: [String: NMFMarker] = [:]
  private var routeLines: [(String, NMFPath)] = []
  private var stopConnections: [String: (selection: MapSelection, line: NMFPolylineOverlay)] = [:]
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
  private weak var webViewForVerification: WKWebView?
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
    // SDK의 회전 처리가 핀치에 의존하므로 회전은 별도 인식기로 처리한다.
    mapView.isRotateGestureEnabled = false
    mapView.isScrollGestureEnabled = true
    mapView.isZoomGestureEnabled = true
    rotationGesture.delegate = self
    rotationGesture.cancelsTouchesInView = false
    mapView.addGestureRecognizer(rotationGesture)
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

  public override func mountChildComponentView(_ childComponentView: UIView, index: Int) {
    gate.insertSubview(childComponentView, at: index)
  }

  public override func unmountChildComponentView(_ childComponentView: UIView, index: Int) {
    navigationSnapshot.finish()
    childComponentView.removeFromSuperview()
  }

  @objc private func rotateMap(_ gesture: UIRotationGestureRecognizer) {
    guard gesture.state == .began || gesture.state == .changed else { return }
    let rotation = gesture.rotation
    gesture.rotation = 0
    guard rotation.isFinite, mapView.bounds.width > 0, mapView.bounds.height > 0 else { return }
    let center = gesture.location(in: mapView)
    let update = NMFCameraUpdate(heading: mapView.cameraPosition.heading - rotation * 180 / .pi)
    update.pivot = CGPoint(x: center.x / mapView.bounds.width, y: center.y / mapView.bounds.height)
    update.animation = .none
    update.reason = Int32(NMFMapChangedByGesture)
    mapView.moveCamera(update)
  }

  public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
    gestureRecognizer === rotationGesture || otherGestureRecognizer === rotationGesture
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    gate.frame = bounds
    navigationSnapshot.attachWebView(in: gate)
    gate.navigationSnapshot = navigationSnapshot
    applyLayout()
  }

  public func handleMessage(_ message: String) {
    guard let bytes = message.data(using: .utf8), bytes.count <= 4_000_000 else { return }
    do {
      let envelope = try JSONDecoder().decode(MapEnvelope.self, from: bytes)
      let session = envelope.payload
      guard envelope.v == 1, envelope.type.hasPrefix("map."),
            !session.sessionId.isEmpty, session.sessionId.count <= 128, session.revision >= 0,
            let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
            let payload = object["payload"] else { return }
      let command = String(envelope.type.dropFirst("map.".count))
      let payloadBytes = try JSONSerialization.data(withJSONObject: payload)
      if command == "mount" || command == "mountHome" {
        if sessionId == session.sessionId && session.revision <= lastRevision { return }
        reset()
        sessionId = session.sessionId
        lastRevision = session.revision
        if command == "mountHome" {
          mountHome(try JSONDecoder().decode(MapHomeMount.self, from: payloadBytes))
        } else {
          mount(try JSONDecoder().decode(MapScene.self, from: payloadBytes))
        }
        return
      }
      guard sessionId == session.sessionId, session.revision > lastRevision else { return }
      lastRevision = session.revision
      switch command {
      case "updateHome":
        if homeMounted { updateHome(try JSONDecoder().decode(MapHomeContent.self, from: payloadBytes)) }
      case "homeCamera":
        if homeMounted {
          pendingHomeCamera = try JSONDecoder().decode(MapHomeCameraCommand.self, from: payloadBytes)
          scheduleInitialFit()
        }
      case "updateScene":
        updateScene(try JSONDecoder().decode(MapSceneState.self, from: payloadBytes))
      case "layout":
        let layout = try JSONDecoder().decode(MapLayout.self, from: payloadBytes)
        guard layout.valid else { fail("지도 표시 영역을 확인하지 못했어요"); return }
        mapLayout = layout
        applyLayout()
        emit("ack", ["appliedRevision": session.revision])
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
    homeMarkers.values.forEach { $0.mapView = nil }
    homeLines.forEach { $0.mapView = nil }
    homeMarkers.removeAll()
    homeLines.removeAll()
    homePaths.removeAll()
    homeMounted = false
    pendingHomeCamera = nil
    homeCameraGesture = false
    stopConnections.values.forEach { $0.line.mapView = nil }
    stopConnections.removeAll()
    imageCache.removeAll()
    mapView.locationOverlay.hidden = true
    mapView.locationOverlay.circleRadius = 0
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
    setWebTopEdgeEffectHidden(false, in: gate)
    updateVerificationSnapshot()
  }

  private func mount(_ nextScene: MapScene) {
    guard !nextScene.stops.isEmpty || !nextScene.fullPath.isEmpty || nextScene.paths.contains(where: { !$0.coordinates.isEmpty }),
          nextScene.stops.allSatisfy({ $0.coordinate.valid && ($0.connectionCoordinate?.valid ?? true) }),
          nextScene.paths.allSatisfy({ $0.coordinates.allSatisfy(\.valid) }),
          nextScene.fullPath.allSatisfy(\.valid) else {
      fail("지도 좌표를 확인하지 못했어요")
      return
    }
    scene = nextScene
    let initialStop = nextScene.stops.first { $0.selection == nextScene.selectedStop }
    let preserveCamera = nextScene.preserveCamera == true
    initialFitPending = !preserveCamera && initialStop == nil
    if !preserveCamera, mapView.cameraPosition.heading != 0 {
      let camera = NMFCameraUpdate(heading: 0)
      camera.animation = .none
      mapView.moveCamera(camera)
    }
    if !preserveCamera, let initialStop {
      let camera = NMFCameraUpdate(scrollTo: initialStop.markerCoordinate.naver, zoomTo: 13)
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
    let mappedDirections = Set(nextScene.paths.filter { $0.coordinates.count > 1 }.map(\.direction))
    let expectedDirections = Set(nextScene.directions ?? nextScene.stops.map(\.direction))
    if nextScene.fullPath.count > 1, mappedDirections.isEmpty || !expectedDirections.isSubset(of: mappedDirections) {
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
      if stop.boardingAllowed, let connection = stop.connectionCoordinate, connection != stop.coordinate,
         let line = NMFPolylineOverlay([stop.coordinate.naver, connection.naver]) {
        line.width = 1.5
        line.color = MapMarkerImages.routeColor.withAlphaComponent(0.65)
        line.pattern = [2, 3]
        line.minZoom = 14
        line.zIndex = 3
        stopConnections[stop.key] = (stop.selection, line)
      }
      let marker = NMFMarker(position: stop.markerCoordinate.naver)
      marker.isForceShowIcon = true
      let label = NMFMarker(position: stop.markerCoordinate.naver)
      label.isForceShowIcon = true
      label.anchor = CGPoint(x: 0.5, y: 0)
      let hidden = stop.direction != nextScene.state.activeDirection
        || (nextScene.selectedStopOnly == true && stop.selection != nextScene.selectedStop)
      marker.hidden = hidden
      label.hidden = hidden
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
    waitForMapReady()
  }

  private func waitForMapReady() {
    applyLayout()
    if mapView.loaded {
      notifyReady()
      return
    }
    let expectedSession = sessionId
    initializationTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
      guard let self, self.sessionId == expectedSession, !self.readySent else { return }
      self.fail("지도를 불러오지 못했어요")
    }
  }

  private func mountHome(_ mount: MapHomeMount) {
    guard mount.camera.valid else { fail("지도 좌표를 확인하지 못했어요"); return }
    homeMounted = true
    // 지도 크기가 결정된 뒤 홈 카메라를 적용해야 프레임 변경으로 중심이 밀리지 않습니다.
    pendingHomeCamera = MapHomeCameraCommand(command: "position", camera: mount.camera, coordinate: nil,
                                             visible: nil, southwest: nil, northeast: nil, animated: nil)
    mapContainer.isHidden = false
    updateHome(mount.content)
    waitForMapReady()
  }

  private func updateHome(_ content: MapHomeContent) {
    if let stations = content.stations { updateHomeStations(stations) }
    if let paths = content.paths { updateHomePaths(paths) }
    if let location = content.location {
      if let coordinate = location.coordinate, coordinate.valid {
        mapView.locationOverlay.location = coordinate.naver
        mapView.locationOverlay.circleRadius = 35
        mapView.locationOverlay.hidden = false
      } else {
        mapView.locationOverlay.hidden = true
      }
    }
    updateVerificationSnapshot()
  }

  private func updateHomeStations(_ stations: MapHomeStations) {
    let stops = stations.stops.filter { !$0.id.isEmpty && $0.coordinate.valid }
    let ids = Set(stops.map(\.id))
    for id in homeMarkers.keys.filter({ !ids.contains($0) }) {
      homeMarkers.removeValue(forKey: id)?.mapView = nil
    }
    for stop in stops {
      let marker = homeMarkers[stop.id] ?? NMFMarker(position: stop.coordinate.naver)
      if homeMarkers[stop.id] == nil {
        let id = stop.id
        marker.touchHandler = { [weak self] _ in
          self?.emit("homeStopPress", ["id": id])
          return true
        }
        homeMarkers[id] = marker
      }
      if marker.position.lat != stop.coordinate.latitude || marker.position.lng != stop.coordinate.longitude {
        marker.position = stop.coordinate.naver
      }
      let selected = stop.id == stations.selectedStopId
      let icon = stopImage(selected: selected, boardingAllowed: true)
      if marker.iconImage !== icon {
        marker.iconImage = icon
        marker.anchor = icon === NMF_MARKER_IMAGE_DEFAULT ? NMF_MARKER_ANCHOR_DEFAULT : CGPoint(x: 0.5, y: 0.5)
        marker.width = icon.image.size.width
        marker.height = icon.image.size.height
      }
      marker.minZoom = selected ? 0 : 13
      marker.captionText = stop.name
      marker.captionMinZoom = selected ? 0 : 17
      marker.isForceShowIcon = selected
      marker.isHideCollidedMarkers = !selected
      marker.isHideCollidedCaptions = !selected
      marker.zIndex = selected ? 15 : 6
      if marker.mapView == nil { marker.mapView = mapView }
    }
  }

  private func updateHomePaths(_ paths: [MapHomePath]) {
    guard homePaths != paths else { return }
    homeLines.forEach { $0.mapView = nil }
    homePaths = paths
    homeLines = paths.compactMap { path in
      guard path.coordinates.count > 1, path.coordinates.allSatisfy(\.valid) else { return nil }
      let line = NMFPath()
      line.path = NMGLineString(points: path.coordinates.map(\.naver))
      line.width = 5
      line.outlineWidth = 0
      line.color = path.active ? MapMarkerImages.routeColor : MapMarkerImages.mutedColor
      line.passedColor = line.color
      line.zIndex = path.active ? 2 : 1
      if path.arrows {
        line.patternInterval = 100
        line.patternIcon = routeArrowImage()
      }
      line.mapView = mapView
      return line
    }
  }

  private func setHomeCamera(_ camera: MapCamera, animated: Bool = false) {
    guard camera.valid else { return }
    let position = NMFCameraPosition(camera.center.naver, zoom: camera.zoom, tilt: 0, heading: camera.heading)
    let update = NMFCameraUpdate(position: position)
    update.animation = animated && !UIAccessibility.isReduceMotionEnabled ? .easeOut : .none
    mapView.moveCamera(update)
  }

  private func homeVisibleRect(_ rect: MapRect, layout: MapLayout) -> CGRect {
    mapView.convert(layout.nativeRect(rect, in: bounds), from: self).intersection(mapView.bounds)
  }

  private func applyHomeCamera() {
    guard homeMounted, readySent, !failed, let layout = mapLayout, !layout.transitioning,
          mapView.bounds.width > 1, mapView.bounds.height > 1, let command = pendingHomeCamera else { return }
    pendingHomeCamera = nil
    switch command.command {
    case "position":
      if let camera = command.camera { setHomeCamera(camera) }
    case "center":
      guard let coordinate = command.coordinate, coordinate.valid else { return }
      let update = NMFCameraUpdate(scrollTo: coordinate.naver)
      if let visible = command.visible, visible.valid {
        let rect = homeVisibleRect(visible, layout: layout)
        if !rect.isEmpty {
          update.pivot = CGPoint(x: rect.midX / mapView.bounds.width, y: rect.midY / mapView.bounds.height)
        }
      }
      update.animation = .none
      mapView.moveCamera(update)
    case "fit":
      guard let southwest = command.southwest, southwest.valid, let northeast = command.northeast, northeast.valid,
            let visible = command.visible, visible.valid else { return }
      fitHome(southwest: southwest, northeast: northeast, visible: homeVisibleRect(visible, layout: layout),
              animated: command.animated == true)
    default: break
    }
  }

  private func fitHome(southwest: MapCoordinate, northeast: MapCoordinate, visible: CGRect, animated: Bool) {
    guard visible.width > 32, visible.height > 24 else { return }
    let coordinates = [southwest, northeast,
      MapCoordinate(latitude: southwest.latitude, longitude: northeast.longitude),
      MapCoordinate(latitude: northeast.latitude, longitude: southwest.longitude)]
    let points = coordinates.map { mapView.projection.point(from: $0.naver) }
    let xs = points.map(\.x)
    let ys = points.map(\.y)
    guard let left = xs.min(), let right = xs.max(), let top = ys.min(), let bottom = ys.max(),
          [left, right, top, bottom].allSatisfy(\.isFinite) else { return }
    let scale = min((visible.width - 32) / max(1, right - left), (visible.height - 24) / max(1, bottom - top))
    let position = mapView.cameraPosition
    let zoom = min(14, max(mapView.minZoomLevel, position.zoom + log2(scale)))
    let ratio = pow(2, position.zoom - zoom)
    let center = mapView.projection.latlng(from: CGPoint(
      x: (left + right) / 2 + (mapView.bounds.midX - visible.midX) * ratio,
      y: (top + bottom) / 2 + (mapView.bounds.midY - visible.midY) * ratio
    ))
    setHomeCamera(MapCamera(center: MapCoordinate(latitude: center.lat, longitude: center.lng),
                            zoom: zoom, heading: position.heading), animated: animated)
  }

  private func reportHomeViewport(gesture: Bool) {
    guard homeMounted, readySent, !failed, pendingHomeCamera == nil,
          mapLayout != nil, !mapContainer.bounds.isEmpty else { return }
    let rect = mapView.convert(mapContainer.bounds, from: mapContainer)
    let corners = [CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY),
                   CGPoint(x: rect.minX, y: rect.maxY), CGPoint(x: rect.maxX, y: rect.maxY)]
      .map { mapView.projection.latlng(from: $0) }
      .map { MapCoordinate(latitude: $0.lat, longitude: $0.lng) }
    let position = mapView.cameraPosition
    let center = MapCoordinate(latitude: position.target.lat, longitude: position.target.lng)
    guard center.valid, corners.allSatisfy(\.valid), position.zoom.isFinite, position.heading.isFinite else { return }
    emit("homeViewport", ["camera": ["center": center.json, "zoom": position.zoom, "heading": position.heading],
                          "corners": corners.map(\.json), "gesture": gesture])
  }

  private func updateScene(_ state: MapSceneState) {
    guard let scene else { return }
    sceneState = state
    for stop in scene.stops {
      guard let marker = stopMarkers[stop.key] else { continue }
      let selected = state.selectedStop == stop.selection
      let active = state.activeDirection == stop.direction
      marker.hidden = !active || (scene.selectedStopOnly == true && !selected)
      stopLabels[stop.key]?.hidden = marker.hidden
      guard !marker.hidden else { continue }
      let icon = stopImage(selected: selected, boardingAllowed: stop.boardingAllowed)
      if marker.iconImage !== icon {
        marker.iconImage = icon
        marker.anchor = icon === NMF_MARKER_IMAGE_DEFAULT ? NMF_MARKER_ANCHOR_DEFAULT : CGPoint(x: 0.5, y: 0.5)
        marker.width = icon.image.size.width
        marker.height = icon.image.size.height
      }
      marker.minZoom = !selected && scene.paths.contains { $0.direction == stop.direction } ? 14 : 0
      marker.zIndex = selected ? 15 : 6
      if let label = stopLabels[stop.key] {
        let icon = overlayImage("label:\(stop.key):\(selected)") {
          MapMarkerImages.stopName(stop.name, selected: selected)
        }
        if label.iconImage !== icon { label.iconImage = icon }
        label.minZoom = selected ? 0 : 14
        label.zIndex = marker.zIndex
      }
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
    for (selection, line) in stopConnections.values {
      let visible = selection.direction == state.activeDirection
        && (scene?.selectedStopOnly != true || selection == state.selectedStop)
      if visible && line.mapView == nil { line.mapView = mapView }
      if !visible && line.mapView != nil { line.mapView = nil }
    }
    let showBothDirections = mapView.cameraPosition.zoom >= 14
    for (direction, line) in routeLines {
      let active = direction == state.activeDirection
      line.color = (active ? MapMarkerImages.routeColor : MapMarkerImages.mutedColor.withAlphaComponent(0.95))
      line.passedColor = line.color
      line.zIndex = active ? 2 : 1
      line.patternIcon = !direction.isEmpty && (active || showBothDirections) ? routeArrowImage() : nil
    }
  }

  private func applyLayout() {
    guard let layout = mapLayout, layout.valid, bounds.width > 0, bounds.height > 0 else { return }
    setWebTopEdgeEffectHidden(true, in: gate)
    let rect = layout.nativeRect(layout.mapRect, in: bounds).intersection(bounds)
    gate.mapRect = rect
    gate.webRects = layout.webRects.map { layout.nativeRect($0, in: bounds) }
    gate.captureAll = layout.captureAll || layout.transitioning || !readySent || failed
    mapContainer.frame = rect.isNull ? .zero : rect
    let bufferHeight = max(bounds.height, rect.height)
    let crop = max(0, (bufferHeight - rect.height) / 2)
    mapView.frame = CGRect(x: 0, y: -crop, width: rect.width, height: bufferHeight)
    mapView.logoAlign = .leftBottom
    let logoInset = min(rect.height, (layout.logoBottomInset ?? 0) * bounds.width / layout.viewport.width)
    mapView.logoMargin = UIEdgeInsets(top: 0, left: 12, bottom: crop + logoInset + 12, right: 0)
    mapView.accessibilityElementsHidden = layout.captureAll || rect.height < 1
    scheduleInitialFit()
    if homeMounted, !layout.transitioning {
      reportHomeViewport(gesture: false)
    }
    updateVerificationSnapshot()
  }

  private func setWebTopEdgeEffectHidden(_ hidden: Bool, in view: UIView) {
    guard #available(iOS 26.0, *) else { return }
    if let webView = view as? WKWebView {
      webView.scrollView.topEdgeEffect.isHidden = hidden
      #if DEBUG
      webViewForVerification = webView
      #endif
      return
    }
    for child in view.subviews { setWebTopEdgeEffectHidden(hidden, in: child) }
  }

  private func scheduleInitialFit() {
    guard readySent, !failed, initialFitPending || pendingCameraCommand != nil || pendingHomeCamera != nil,
          !initialFitScheduled,
          mapContainer.bounds.width > 1, mapContainer.bounds.height > 1, window != nil else { return }
    initialFitScheduled = true
    let expectedSession = sessionId
    DispatchQueue.main.async { [weak self] in
      guard let self, self.sessionId == expectedSession else { return }
      self.initialFitScheduled = false
      // 검색 키보드와 웹 시트의 전환이 끝난 표시 영역으로 최초 카메라를 맞춘다.
      guard self.readySent, !self.failed, let layout = self.mapLayout,
            !layout.captureAll, !layout.transitioning,
            abs(layout.viewport.height * self.bounds.width / layout.viewport.width - self.bounds.height) <= 1 else { return }
      self.mapContainer.layoutIfNeeded()
      self.mapView.setNeedsLayout()
      self.mapView.layoutIfNeeded()
      if self.homeMounted {
        self.applyHomeCamera()
        self.reportHomeViewport(gesture: false)
      } else if let command = self.pendingCameraCommand {
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
    // 잘린 지도와 상태바 안전 영역을 함께 제외한 화면 안에 전체 경로를 맞춘다.
    let visibleRect = mapView.convert(mapContainer.bounds, from: mapContainer)
      .intersection(mapView.convert(safeAreaLayoutGuide.layoutFrame, from: self))
    let area = NMGLatLngBounds(southWest: NMGLatLng(lat: south, lng: west),
                              northEast: NMGLatLng(lat: north, lng: east))
    mapView.moveCamera(NMFCameraUpdate(fit: area,
      paddingInsets: UIEdgeInsets(top: visibleRect.minY + 28, left: visibleRect.minX + 12,
                                 bottom: mapView.bounds.height - visibleRect.maxY + 28,
                                 right: mapView.bounds.width - visibleRect.maxX + 12)))
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
      coordinate = scene?.stops.first(where: { $0.selection == command.selection })?.markerCoordinate.naver
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
    pendingCameraCommand = nil
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
    navigationSnapshot.finish()
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
    for marker in stopMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for marker in stopLabels.values { marker.mapView = nil; marker.mapView = mapView }
    for marker in vehicleMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for (_, line) in routeLines { line.mapView = nil; line.mapView = mapView }
    for marker in homeMarkers.values { marker.mapView = nil; marker.mapView = mapView }
    for line in homeLines { line.mapView = nil; line.mapView = mapView }
    applyLayout()
    refreshVehicleImages()
    emit("ready", [:])
    updateVerificationSnapshot()
    #if DEBUG
    NSLog("[SalmonNativeMap] native SDK ready")
    #endif
  }

  public func mapViewDidFinishLoadingMap(_ mapView: NMFMapView) { notifyReady() }

  public func mapView(_ mapView: NMFMapView, cameraWillChangeByReason reason: Int, animated: Bool) {
    homeCameraGesture = reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl
    if reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl {
      pendingCameraCommand = nil
      initialFitPending = false
      cancelVehicleFocus()
    }
    #if DEBUG
    lastCameraChangeReason = reason
    NSLog("[SalmonNativeMap] camera willChange reason=%@ animated=%@",
          reason == NMFMapChangedByGesture ? "gesture" : "programmatic", animated ? "true" : "false")
    #endif
  }

  public func mapView(_ mapView: NMFMapView, cameraIsChangingByReason reason: Int) {
    if reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl { cancelVehicleFocus() }
    refreshVehicleImages()
    refreshRouteStyles()
    #if DEBUG
    lastCameraChangeReason = reason
    #endif
  }

  public func mapViewCameraIdle(_ mapView: NMFMapView) {
    refreshVehicleImages()
    refreshRouteStyles()
    reportHomeViewport(gesture: homeCameraGesture)
    homeCameraGesture = false
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
    let route = scene.map { $0.fullPath.isEmpty ? $0.paths.flatMap(\.coordinates) : $0.fullPath } ?? []
    let coordinates = route.isEmpty ? scene?.stops.map(\.coordinate) ?? [] : route
    let visibleRect = mapView.convert(mapContainer.bounds, from: mapContainer)
      .intersection(mapView.convert(safeAreaLayoutGuide.layoutFrame, from: self))
    var snapshot: [String: Any] = [
      "sessionId": sessionId ?? "",
      "routeId": scene?.routeId ?? "",
      "home": homeMounted,
      "ready": readySent && !failed && !initialFitPending && !initialFitScheduled,
      "zoom": mapView.cameraPosition.zoom,
      "heading": mapView.cameraPosition.heading,
      "latitude": mapView.cameraPosition.target.lat,
      "longitude": mapView.cameraPosition.target.lng,
      "gestureMoves": gestureMoveCount,
      "visibleMapHeight": mapContainer.bounds.height,
      "captureAll": gate.captureAll,
      "stops": (homeMounted ? homeMarkers : stopMarkers).values.filter { $0.mapView === mapView }.count,
      "vehicles": vehicleMarkers.values.filter { $0.mapView === mapView }.count,
      "maximumSwipeOffset": navigationSnapshot.maximumSwipeOffset,
      "routeFitsVisibleMap": !coordinates.isEmpty && coordinates.allSatisfy {
        visibleRect.contains(mapView.projection.point(from: $0.naver))
      },
    ]
    if #available(iOS 26.0, *) {
      snapshot["webTopEdgeEffectHidden"] = webViewForVerification?.scrollView.topEdgeEffect.isHidden == true
    }
    if let marker = homeMarkers.values.first {
      let point = mapView.convert(mapView.projection.point(from: marker.position), to: nil)
      if point.x.isFinite && point.y.isFinite {
        snapshot["homeStopPoint"] = ["x": point.x, "y": point.y]
      }
    }
    guard let data = try? JSONSerialization.data(withJSONObject: snapshot),
          let value = String(data: data, encoding: .utf8) else { return }
    mapView.verificationValue = value
    #endif
  }

  private func emit(_ type: String, _ payload: [String: Any]) {
    guard let sessionId else { return }
    // sessionId와 revision은 세션 값 자리라서 이벤트 payload 필드 이름으로 쓰지 않는다.
    var body = payload
    body["sessionId"] = sessionId
    body["revision"] = max(0, lastRevision)
    let event: [String: Any] = ["v": 1, "type": "map.\(type)", "payload": body]
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

  private func stopImage(selected: Bool, boardingAllowed: Bool) -> NMFOverlayImage {
    let key = "stop:\(selected):\(boardingAllowed)"
    if let cached = imageCache[key] { return cached }
    let icon = MapMarkerImages.stop(selected: selected, boardingAllowed: boardingAllowed)
      .map { NMFOverlayImage(image: $0) } ?? NMF_MARKER_IMAGE_DEFAULT
    imageCache[key] = icon
    return icon
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
