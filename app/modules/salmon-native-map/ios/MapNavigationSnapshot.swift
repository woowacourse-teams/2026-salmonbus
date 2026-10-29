import UIKit
import WebKit

final class MapNavigationSnapshot: NSObject {
  private weak var mapContainer: UIView?
  private weak var webView: WKWebView?
  private var snapshot: UIView?
  private var displayLink: CADisplayLink?
  private var gestureEndedAt: CFTimeInterval?
  #if DEBUG
  private(set) var maximumSwipeOffset: CGFloat = 0
  #endif

  init(mapContainer: UIView) {
    self.mapContainer = mapContainer
    super.init()
  }

  func attachWebView(in view: UIView) {
    if webView?.isDescendant(of: view) == true { return }
    func findWebView(in view: UIView) -> WKWebView? {
      if let webView = view as? WKWebView { return webView }
      return view.subviews.lazy.compactMap { findWebView(in: $0) }.first
    }
    finish()
    webView?.gestureRecognizers?.forEach { $0.removeTarget(self, action: #selector(swiped(_:))) }
    webView = findWebView(in: view)
    for gesture in webView?.gestureRecognizers ?? [] where gesture is UIPanGestureRecognizer {
      gesture.addTarget(self, action: #selector(swiped(_:)))
    }
  }

  func capturesNavigationEdge(_ point: CGPoint, in view: UIView) -> Bool {
    guard let webView, webView.allowsBackForwardNavigationGestures else { return false }
    let leftToRight = webView.effectiveUserInterfaceLayoutDirection == .leftToRight
    return (point.x <= 20 && (leftToRight ? webView.canGoBack : webView.canGoForward))
      || (point.x >= view.bounds.width - 20 && (leftToRight ? webView.canGoForward : webView.canGoBack))
  }

  @objc private func swiped(_ gesture: UIPanGestureRecognizer) {
    switch gesture.state {
    case .began:
      guard snapshot == nil, let mapContainer, !mapContainer.isHidden, let webView,
            webView.allowsBackForwardNavigationGestures,
            let snapshot = mapContainer.snapshotView(afterScreenUpdates: false) else { return }
      let rect = mapContainer.convert(mapContainer.bounds, to: webView)
      snapshot.frame = rect.offsetBy(dx: webView.scrollView.contentOffset.x, dy: webView.scrollView.contentOffset.y)
      snapshot.isUserInteractionEnabled = false
      snapshot.accessibilityElementsHidden = true
      webView.scrollView.insertSubview(snapshot, at: 0)
      self.snapshot = snapshot
      mapContainer.alpha = 0
      gestureEndedAt = nil
      #if DEBUG
      maximumSwipeOffset = 0
      #endif
      displayLink = CADisplayLink(target: self, selector: #selector(checkTransition))
      displayLink?.add(to: .main, forMode: .common)
    case .ended, .cancelled, .failed:
      gestureEndedAt = CACurrentMediaTime()
      checkTransition()
    default: break
    }
  }

  @objc private func checkTransition() {
    guard let webView, webView.window != nil else { finish(); return }
    #if DEBUG
    if let snapshot {
      let offset = snapshot.convert(.zero, to: webView).x - snapshot.frame.minX + webView.scrollView.contentOffset.x
      maximumSwipeOffset = max(maximumSwipeOffset, abs(offset))
    }
    #endif
    if let gestureEndedAt,
       webView.scrollView.superview === webView || CACurrentMediaTime() - gestureEndedAt >= 1 {
      finish()
    }
  }

  func finish() {
    displayLink?.invalidate()
    displayLink = nil
    snapshot?.removeFromSuperview()
    snapshot = nil
    gestureEndedAt = nil
    mapContainer?.alpha = 1
  }
}
