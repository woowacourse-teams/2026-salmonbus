import UIKit
import WebKit

private final class HistoryWebView: WKWebView {
  var backAvailable = false
  var forwardAvailable = false
  override var canGoBack: Bool { backAvailable }
  override var canGoForward: Bool { forwardAvailable }
}

@main
struct MapNavigationSnapshotChecks {
  static func main() {
    let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    let map = UIView(frame: host.bounds)
    let overlay = UIView(frame: host.bounds)
    let web = HistoryWebView(frame: host.bounds)
    // RNCWebView can have siblings after WKWebView; discovering it must stop at the first match.
    overlay.addSubview(web)
    overlay.addSubview(UIView())
    host.addSubview(map)
    host.addSubview(overlay)
    web.allowsBackForwardNavigationGestures = true
    let navigation = MapNavigationSnapshot(mapContainer: map)
    navigation.attachWebView(in: overlay)
    func captures(_ x: CGFloat) -> Bool {
      navigation.capturesNavigationEdge(CGPoint(x: x, y: 200), in: overlay)
    }

    precondition(!captures(1) && !captures(389), "No history must leave map edges available")
    web.backAvailable = true
    precondition(captures(1) && !captures(21) && !captures(389))
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
    print("PASS: WebView discovery, history edges, RTL, disabled navigation, idempotent cleanup")
  }
}
