import XCTest

final class NativeMapInteractionTests: XCTestCase {
  private var app: XCUIApplication!

  private struct MapState: Decodable {
    let routeId: String
    let ready: Bool
    let zoom: Double
    let gestureMoves: Int
    let visibleMapHeight: Double
    let captureAll: Bool
    let stops: Int
    let vehicles: Int
    let maximumSwipeOffset: Double
    let routeFitsVisibleMap: Bool
    let webTopEdgeEffectHidden: Bool?
  }

  override func setUpWithError() throws {
    continueAfterFailure = false
    app = XCUIApplication(bundleIdentifier: "com.salmonbus.app")
    app.launchArguments += ["--native-map-ui-testing"]
    app.launch()
    let search = app.webViews.textFields.firstMatch
    XCTAssertTrue(search.waitForExistence(timeout: 20))
    search.tap()
    search.typeText("3330")
    let route = app.webViews.buttons.containing(NSPredicate(format: "label CONTAINS %@", "3330")).firstMatch
    XCTAssertTrue(route.waitForExistence(timeout: 20), "The fixture route must be available in search")
    route.tap()
  }

  func testNativePanWebSheetAndDoubleTap() throws {
    let initial = try waitForState("native map is ready with its route and vehicles", timeout: 90) {
      $0.ready && $0.routeId == "200000001" && !$0.captureAll
        && $0.visibleMapHeight > 180 && $0.stops > 0 && $0.vehicles > 0
    }
    attachScreenshot("01-native-map-and-web-sheet")
    XCTAssertTrue(initial.routeFitsVisibleMap, "The full route must fit between the safe area and the web sheet")
    if #available(iOS 26.0, *) {
      XCTAssertEqual(initial.webTopEdgeEffectHidden, true, "The web overlay must not blur the native map's top edge")
    }

    let y = CGFloat(initial.visibleMapHeight * 0.48)
    let start = coordinate(x: app.frame.width * 0.76, y: y)
    let end = coordinate(x: app.frame.width * 0.24, y: y + 35)
    start.press(forDuration: 0.15, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.15)
    let panned = try waitForState("a real UIKit pan changes the native camera target", timeout: 15) {
      $0.gestureMoves > initial.gestureMoves && !$0.captureAll
    }
    attachScreenshot("02-native-pan-camera-moved")

    let handleY = CGFloat(panned.visibleMapHeight) + 14
    let sheetStart = coordinate(x: app.frame.width * 0.5, y: handleY)
    let sheetEnd = coordinate(x: app.frame.width * 0.5, y: min(app.frame.height - 110, handleY + 200))
    sheetStart.press(forDuration: 0.15, thenDragTo: sheetEnd, withVelocity: .slow, thenHoldForDuration: 0.2)
    let sheetMoved = try waitForState("the web sheet moves and releases its geometry lock", timeout: 15) {
      abs($0.visibleMapHeight - panned.visibleMapHeight) > 40 && !$0.captureAll
    }
    XCTAssertEqual(sheetMoved.gestureMoves, panned.gestureMoves, "Web sheet drag must not pan the native map")
    XCTAssertEqual(sheetMoved.zoom, panned.zoom, accuracy: 0.001, "A settled layout must preserve the user's camera zoom")
    attachScreenshot("03-web-sheet-drag-map-camera-stayed")

    let doubleTap = coordinate(x: app.frame.width * 0.7, y: CGFloat(sheetMoved.visibleMapHeight * 0.42))
    doubleTap.doubleTap()
    let zoomed = try waitForState("native SDK double tap zooms the map", timeout: 15) {
      $0.zoom > sheetMoved.zoom + 0.25 && !$0.captureAll
    }
    XCTAssertGreaterThan(zoomed.zoom, sheetMoved.zoom)
    attachScreenshot("04-native-double-tap-zoom")
  }

  func testBackSwipeCancelAndBackLink() throws {
    let initial = try waitForState("native map is ready before back navigation", timeout: 90) {
      $0.ready && !$0.captureAll && $0.visibleMapHeight > 180 && $0.stops > 0
    }
    attachScreenshot("05-before-back-swipe")

    let y = CGFloat(initial.visibleMapHeight * 0.5)
    let edge = coordinate(x: 1, y: y)
    edge.press(forDuration: 0.05, thenDragTo: coordinate(x: app.frame.width * 0.25, y: y),
               withVelocity: .slow, thenHoldForDuration: 0.5)
    let restored = try waitForState("cancelled back swipe restores the native map", timeout: 15) {
      $0.ready && !$0.captureAll && $0.stops == initial.stops
    }
    let doubleTap = coordinate(x: app.frame.width * 0.65, y: y)
    doubleTap.doubleTap()
    let zoomed = try waitForState("restored native map still receives gestures", timeout: 15) {
      $0.zoom > restored.zoom + 0.25
    }
    XCTAssertGreaterThan(zoomed.maximumSwipeOffset, 30, "The native map snapshot must move with WebKit's back swipe")
    attachScreenshot("06-cancelled-back-swipe-map-restored")

    app.links["노선 선택으로 돌아가기"].tap()
    XCTAssertTrue(app.textFields["번호를 입력하면 탑승 확률을 알려드려요"].waitForExistence(timeout: 15),
                  "The back link must still work after cancelling a swipe")
    attachScreenshot("07-back-link-home-after-cancel")
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
