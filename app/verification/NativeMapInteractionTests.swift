import XCTest

/// Runs against a real NMFMapView and the existing web Bottom Sheet, with deterministic API data.
final class NativeMapInteractionTests: XCTestCase {
  private var app: XCUIApplication!

  private struct MapState: Decodable {
    let ready: Bool
    let zoom: Double
    let gestureMoves: Int
    let visibleMapHeight: Double
    let captureAll: Bool
    let stops: Int
    let vehicles: Int
  }

  override func setUpWithError() throws {
    continueAfterFailure = false
    app = XCUIApplication(bundleIdentifier: "com.salmonbus.app")
    app.launchArguments += ["--native-map-ui-testing"]
    app.launch()
  }

  func testNativePanWebSheetAndDoubleTap() throws {
    let initial = try waitForState("native map is ready with its route and vehicles", timeout: 90) {
      $0.ready && !$0.captureAll && $0.visibleMapHeight > 180 && $0.stops > 0 && $0.vehicles > 0
    }
    attachScreenshot("01-native-map-and-web-sheet")

    let y = CGFloat(initial.visibleMapHeight * 0.48)
    let start = coordinate(x: app.frame.width * 0.76, y: y)
    let end = coordinate(x: app.frame.width * 0.24, y: y + 35)
    start.press(forDuration: 0.15, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.15)
    let panned = try waitForState("a real UIKit pan changes the native camera target", timeout: 15) {
      $0.gestureMoves > initial.gestureMoves && !$0.captureAll
    }
    attachScreenshot("02-native-pan-camera-moved")

    // A touch starts on the web sheet handle. UIKit keeps that same touch in WebKit while it moves.
    let handleY = CGFloat(panned.visibleMapHeight) + 14
    let sheetStart = coordinate(x: app.frame.width * 0.5, y: handleY)
    let sheetEnd = coordinate(x: app.frame.width * 0.5, y: min(app.frame.height - 110, handleY + 200))
    sheetStart.press(forDuration: 0.15, thenDragTo: sheetEnd, withVelocity: .slow, thenHoldForDuration: 0.2)
    let sheetMoved = try waitForState("the web sheet moves and releases its geometry lock", timeout: 15) {
      abs($0.visibleMapHeight - panned.visibleMapHeight) > 40 && !$0.captureAll
    }
    XCTAssertEqual(sheetMoved.gestureMoves, panned.gestureMoves, "Web sheet drag must not pan the native map")
    attachScreenshot("03-web-sheet-drag-map-camera-stayed")

    let doubleTap = coordinate(x: app.frame.width * 0.7, y: CGFloat(sheetMoved.visibleMapHeight * 0.42))
    doubleTap.doubleTap()
    let zoomed = try waitForState("native SDK double tap zooms the map", timeout: 15) {
      $0.zoom > sheetMoved.zoom + 0.25 && !$0.captureAll
    }
    XCTAssertGreaterThan(zoomed.zoom, sheetMoved.zoom)
    attachScreenshot("04-native-double-tap-zoom")
  }

  private var map: XCUIElement {
    app.descendants(matching: .any).matching(identifier: "salmon-native-map").firstMatch
  }

  private func readState() -> MapState? {
    guard map.exists, let value = map.value as? String, let data = value.data(using: .utf8) else { return nil }
    return try? JSONDecoder().decode(MapState.self, from: data)
  }

  private func waitForState(_ description: String, timeout: TimeInterval,
                            predicate: @escaping (MapState) -> Bool) throws -> MapState {
    var latest: MapState?
    let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
      guard let state = self?.readState() else { return false }
      latest = state
      return predicate(state)
    }, object: nil)
    guard XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed, let latest else {
      attachScreenshot("failure-\(description)")
      XCTFail("Timed out: \(description). Last native state: \(String(describing: latest))")
      throw VerificationError.timedOut
    }
    return latest
  }

  private func coordinate(x: CGFloat, y: CGFloat) -> XCUICoordinate {
    app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0)).withOffset(CGVector(dx: x, dy: y))
  }

  private func attachScreenshot(_ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }

  private enum VerificationError: Error { case timedOut }
}
