import UIKit
import WebKit

final class MapNavigationSnapshot: NSObject {
  private weak var mapContainer: UIView?
  private weak var webView: WKWebView?
  private var snapshot: UIView?
  private var displayLink: CADisplayLink?
  private var gestureEndedAt: CFTimeInterval?
  private var historyGestures: [UIGestureRecognizer] = []
  private var historySwipeAllowed = true
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
    guard let nextWebView = findWebView(in: view), nextWebView.scrollView.superview === nextWebView,
          let mapContainer else { return }
    detachWebView()
    webView = nextWebView
    // WebKit이 이전 화면을 캡처할 때 웹 UI와 네이티브 지도를 함께 담는다.
    let rect = mapContainer.convert(mapContainer.bounds, to: nextWebView)
    nextWebView.insertSubview(mapContainer, belowSubview: nextWebView.scrollView)
    mapContainer.frame = rect
    historyGestures = (nextWebView.gestureRecognizers ?? []).filter { $0 is UIPanGestureRecognizer && $0.isEnabled }
    for gesture in historyGestures {
      gesture.addTarget(self, action: #selector(swiped(_:)))
    }
    applyHistorySwipePolicy()
  }

  func detachWebView() {
    for gesture in historyGestures {
      gesture.removeTarget(self, action: #selector(swiped(_:)))
      gesture.isEnabled = true
    }
    historyGestures.removeAll()
    finish()
    webView = nil
  }

  func setHistorySwipeAllowed(_ allowed: Bool) {
    historySwipeAllowed = allowed
    if displayLink == nil { applyHistorySwipePolicy() }
  }

  private func applyHistorySwipePolicy() {
    // WebKit의 방문 기록 캡처는 켜 둔 채 가장자리 스와이프만 막는다.
    for gesture in historyGestures where gesture.isEnabled != historySwipeAllowed {
      gesture.isEnabled = historySwipeAllowed
    }
  }

  func capturesNavigationEdge(_ point: CGPoint, in view: UIView) -> Bool {
    guard historySwipeAllowed, let webView, webView.allowsBackForwardNavigationGestures else { return false }
    let leftToRight = webView.effectiveUserInterfaceLayoutDirection == .leftToRight
    return (point.x <= 20 && (leftToRight ? webView.canGoBack : webView.canGoForward))
      || (point.x >= view.bounds.width - 20 && (leftToRight ? webView.canGoForward : webView.canGoBack))
  }

  @objc private func swiped(_ gesture: UIPanGestureRecognizer) {
    switch gesture.state {
    case .began:
      guard displayLink == nil, historySwipeAllowed, let webView,
            webView.allowsBackForwardNavigationGestures else { return }
      if let mapContainer, !mapContainer.isHidden,
         let snapshot = mapContainer.snapshotView(afterScreenUpdates: false) {
        let rect = mapContainer.convert(mapContainer.bounds, to: webView)
        snapshot.frame = rect.offsetBy(dx: webView.scrollView.contentOffset.x, dy: webView.scrollView.contentOffset.y)
        snapshot.isUserInteractionEnabled = false
        snapshot.accessibilityElementsHidden = true
        webView.scrollView.insertSubview(snapshot, at: 0)
        self.snapshot = snapshot
        mapContainer.alpha = 0
      }
      gestureEndedAt = nil
      #if DEBUG
      maximumSwipeOffset = 0
      #endif
      displayLink = CADisplayLink(target: self, selector: #selector(checkTransition))
      displayLink?.add(to: .main, forMode: .common)
    case .ended, .cancelled, .failed:
      guard displayLink != nil else { return }
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
    applyHistorySwipePolicy()
  }
}
