import UIKit
import WebKit

private final class HistoryWebView: WKWebView {
  var backAvailable = false
  var forwardAvailable = false
  override var canGoBack: Bool { backAvailable }
  override var canGoForward: Bool { forwardAvailable }
}

private final class TestPanGesture: UIPanGestureRecognizer {
  var simulatedState: UIGestureRecognizer.State = .began
  override var state: UIGestureRecognizer.State {
    get { simulatedState }
    set { simulatedState = newValue }
  }
}

private final class SnapshotMapView: UIView {
  override func snapshotView(afterScreenUpdates afterUpdates: Bool) -> UIView? { UIView(frame: bounds) }
}

@main
struct MapNavigationSnapshotChecks {
  static func main() {
    let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    let map = SnapshotMapView(frame: host.bounds)
    let overlay = UIView(frame: host.bounds)
    let web = HistoryWebView(frame: host.bounds)
    let historyPan = TestPanGesture()
    web.addGestureRecognizer(historyPan)
    let disabledPan = UIPanGestureRecognizer()
    disabledPan.isEnabled = false
    web.addGestureRecognizer(disabledPan)
    overlay.addSubview(web)
    overlay.addSubview(UIView())
    host.addSubview(map)
    host.addSubview(overlay)
    web.allowsBackForwardNavigationGestures = true
    let navigation = MapNavigationSnapshot(mapContainer: map)
    navigation.attachWebView(in: overlay)
    precondition(map.superview === web, "이전 화면 캡처에 지도를 포함해야 한다")
    func captures(_ x: CGFloat) -> Bool {
      navigation.capturesNavigationEdge(CGPoint(x: x, y: 200), in: overlay)
    }

    precondition(!captures(1) && !captures(389), "No history must leave map edges available")
    web.backAvailable = true
    precondition(captures(1) && !captures(21) && !captures(389))
    navigation.setHistorySwipeAllowed(false)
    precondition(!captures(1) && !historyPan.isEnabled && web.allowsBackForwardNavigationGestures,
                 "탭에서는 스와이프만 막고 WebKit의 화면 캡처는 유지해야 한다")
    navigation.setHistorySwipeAllowed(true)
    precondition(captures(1) && historyPan.isEnabled)
    precondition(!disabledPan.isEnabled, "WebKit이 꺼 둔 제스처는 활성화하지 않아야 한다")
    web.forwardAvailable = true
    precondition(captures(1) && captures(389) && !captures(195))

    web.semanticContentAttribute = .forceRightToLeft
    web.forwardAvailable = false
    precondition(!captures(1) && captures(389), "RTL must reverse navigation edges")
    web.semanticContentAttribute = .forceLeftToRight
    web.allowsBackForwardNavigationGestures = false
    precondition(!captures(1) && !captures(389))

    map.alpha = 0
    map.isHidden = true
    navigation.finish()
    navigation.finish()
    precondition(map.alpha == 1 && map.isHidden, "Cleanup must preserve an unmounted map's hidden state")

    let window = UIWindow(frame: host.bounds)
    let controller = UIViewController()
    controller.view = host
    window.rootViewController = controller
    window.makeKeyAndVisible()
    map.isHidden = false
    web.allowsBackForwardNavigationGestures = true
    let gesture = TestPanGesture()
    navigation.perform(NSSelectorFromString("swiped:"), with: gesture)
    precondition(map.alpha == 0, "A swipe must hide the real map behind its snapshot")
    let snapshot = web.scrollView.subviews.first!
    let transitionContainer = UIView(frame: web.bounds)
    web.addSubview(transitionContainer)
    transitionContainer.addSubview(web.scrollView)
    gesture.simulatedState = .ended
    navigation.perform(NSSelectorFromString("swiped:"), with: gesture)
    precondition(map.alpha == 0 && snapshot.superview != nil, "The transition must not be cut off immediately")
    RunLoop.main.run(until: Date().addingTimeInterval(1.2))
    precondition(web.scrollView.superview === transitionContainer, "The test must keep WebKit's scroll view displaced")
    precondition(map.alpha == 1 && snapshot.superview == nil,
                 "A stuck WebKit transition must restore the map and remove its snapshot after one second")
    web.addSubview(web.scrollView)
    gesture.simulatedState = .began
    navigation.perform(NSSelectorFromString("swiped:"), with: gesture)
    precondition(map.alpha == 0, "A new swipe must still start after timeout recovery")
    navigation.setHistorySwipeAllowed(false)
    precondition(historyPan.isEnabled, "진행 중인 뒤로가기 전환은 끝날 때까지 유지해야 한다")
    gesture.simulatedState = .cancelled
    navigation.perform(NSSelectorFromString("swiped:"), with: gesture)
    precondition(map.alpha == 1, "A returned scroll view must restore the map without waiting for the timeout")
    precondition(!historyPan.isEnabled, "전환이 끝나면 탭에서 스와이프를 다시 막아야 한다")
    window.isHidden = true
    print("PASS: WebView discovery, history edges, RTL, disabled navigation, idempotent cleanup, stuck transition recovery")
  }
}
