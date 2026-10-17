import CoreGraphics
import Foundation
import NMapsMap

struct MapEnvelope: Decodable {
  let v: Int
  let sessionId: String
  let revision: Int
  let type: String
}

struct MapCoordinate: Decodable {
  let latitude: Double
  let longitude: Double

  var valid: Bool {
    latitude.isFinite && longitude.isFinite && abs(latitude) <= 90 && abs(longitude) <= 180
  }

  var naver: NMGLatLng { NMGLatLng(lat: latitude, lng: longitude) }
  var json: [String: Double] { ["latitude": latitude, "longitude": longitude] }
}

struct MapSelection: Decodable, Equatable {
  let direction: String
  let sequence: Int
  var json: [String: Any] { ["direction": direction, "sequence": sequence] }
}

struct MapStop: Decodable {
  let direction: String
  let sequence: Int
  let coordinate: MapCoordinate
  let name: String
  let boardingAllowed: Bool
  let terminalStopName: String
  var selection: MapSelection { MapSelection(direction: direction, sequence: sequence) }
  var key: String { "\(direction):\(sequence)" }
}

struct MapPath: Decodable {
  let direction: String
  let coordinates: [MapCoordinate]
}

struct MapVehicle: Decodable {
  let key: String
  let direction: String
  let currentStopSequence: Int
  let coordinate: MapCoordinate
  let caption: String
  let label: String
  let remainingSeats: Int?
  var selection: MapSelection { MapSelection(direction: direction, sequence: currentStopSequence) }
}

struct MapScene: Decodable {
  let routeId: String
  let referenceVersionId: String
  let stops: [MapStop]
  let paths: [MapPath]
  let fullPath: [MapCoordinate]
  let activeDirection: String
  let selectedStop: MapSelection?
  let selectedVehicleKey: String?
  let vehicles: [MapVehicle]

  var state: MapSceneState {
    MapSceneState(activeDirection: activeDirection, selectedStop: selectedStop,
                  selectedVehicleKey: selectedVehicleKey, vehicles: vehicles)
  }
}

struct MapSceneState: Decodable {
  let activeDirection: String
  let selectedStop: MapSelection?
  let selectedVehicleKey: String?
  let vehicles: [MapVehicle]
}

struct MapRect: Decodable {
  let x: Double
  let y: Double
  let width: Double
  let height: Double
  var valid: Bool { [x, y, width, height].allSatisfy(\.isFinite) && width >= 0 && height >= 0 }
}

struct MapViewport: Decodable {
  let width: Double
  let height: Double
  let offsetTop: Double
  let offsetLeft: Double
  let scale: Double
}

struct MapLayout: Decodable {
  let viewport: MapViewport
  let mapRect: MapRect
  let webRects: [MapRect]
  let captureAll: Bool
  let transitioning: Bool

  var valid: Bool {
    viewport.width.isFinite && viewport.width > 0 && viewport.height.isFinite && viewport.height > 0
      && viewport.offsetTop.isFinite && viewport.offsetLeft.isFinite
      && viewport.scale.isFinite && viewport.scale > 0 && mapRect.valid && webRects.allSatisfy(\.valid)
  }

  // A CSS point is in the layout viewport. UIKit points are in the visible WebView bounds.
  func nativeRect(_ rect: MapRect, in bounds: CGRect) -> CGRect {
    let sx = bounds.width / viewport.width
    // Keyboard changes visible viewport height without resizing WKWebView. Use one uniform scale.
    let sy = sx
    return CGRect(x: (rect.x - viewport.offsetLeft) * sx,
                  y: (rect.y - viewport.offsetTop) * sy,
                  width: rect.width * sx, height: rect.height * sy)
  }
}

struct MotionKeyframe: Decodable {
  let latitude: Double
  let longitude: Double
  let progress: Double
  var coordinate: MapCoordinate { MapCoordinate(latitude: latitude, longitude: longitude) }
}

struct VehicleMotionPlan: Decodable {
  let key: String
  let signature: String
  let path: [MotionKeyframe]
  let durationMs: Double
}

struct MotionPayload: Decodable { let vehicles: [VehicleMotionPlan] }
struct LocationRequest: Decodable { let requestId: Int }

struct CameraCommand: Decodable {
  let command: String
  let selection: MapSelection?
  let key: String?
  let focusMarker: Bool?
  let coordinate: MapCoordinate?
}
