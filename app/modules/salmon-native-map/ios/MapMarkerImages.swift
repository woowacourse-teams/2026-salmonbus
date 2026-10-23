import UIKit

// Sizes, colors and glyphs follow frontend/.../map/RouteMap.css.ts and naverMapAdapter.ts.
enum MapMarkerImages {
  static let stopColor = UIColor(red: 201/255, green: 90/255, blue: 80/255, alpha: 1)
  static let mutedColor = UIColor(red: 91/255, green: 100/255, blue: 114/255, alpha: 1)
  static let busColor = UIColor(red: 229/255, green: 61/255, blue: 77/255, alpha: 1)
  static let routeColor = UIColor(red: 237/255, green: 119/255, blue: 104/255, alpha: 0.95)

  private static func font(size: CGFloat, weight: String) -> UIFont {
    UIFont(name: "AppleSDGothicNeo-\(weight)", size: size) ?? .systemFont(ofSize: size, weight: .bold)
  }

  static func stop(selected: Bool, active: Bool, boardingAllowed: Bool) -> UIImage {
    let size: CGFloat = selected ? 32 : 24
    let border: CGFloat = selected ? 3 : 2
    let color = active ? stopColor : mutedColor
    return UIGraphicsImageRenderer(size: CGSize(width: size + 12, height: size + 12)).image { context in
      let circle = UIBezierPath(ovalIn: CGRect(x: 6 + border/2, y: 6 + border/2,
                                             width: size - border, height: size - border))
      context.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 4,
                                  color: UIColor.black.withAlphaComponent(0.13).cgColor)
      (selected ? stopColor : active ? .white : UIColor(white: 237/255, alpha: 1)).setFill()
      circle.fill()
      context.cgContext.setShadow(offset: .zero, blur: 0)
      color.setStroke()
      circle.lineWidth = border
      circle.stroke()
      let iconSize = (size - border * 2) * 0.65
      // PNGs are rendered at 3x from the web's original SVGs, including bus-front.svg.
      UIImage(named: boardingAllowed ? "stop-boarding" : "stop-nonboarding")?
        .withTintColor(selected ? .white : color, renderingMode: .alwaysOriginal)
        .draw(in: CGRect(x: 6 + (size - iconSize)/2, y: 6 + (size - iconSize)/2,
                         width: iconSize, height: iconSize))
    }
  }

  static func stopName(_ name: String, selected: Bool, above: Bool) -> UIImage {
    let text = name as NSString
    let attributes: [NSAttributedString.Key: Any] = [
      .font: font(size: 13, weight: "ExtraBold"), .foregroundColor: UIColor.white,
    ]
    let textSize = text.size(withAttributes: attributes)
    let gap: CGFloat = (selected ? 16 : 12) + 4
    let size = CGSize(width: ceil(textSize.width) + 10, height: ceil(textSize.height) + 6 + gap)
    return UIGraphicsImageRenderer(size: size).image { _ in
      let rect = CGRect(x: 5, y: above ? 3 : gap + 3, width: size.width - 10, height: size.height - gap - 6)
      var outline = attributes
      outline[.strokeColor] = UIColor.black
      outline[.strokeWidth] = 200.0/13
      text.draw(in: rect, withAttributes: outline)
      text.draw(in: rect, withAttributes: attributes)
    }
  }

  static func vehicle(caption: String, showCount: Bool, stackIndex: Int) -> UIImage {
    let text = caption as NSString
    let attributes: [NSAttributedString.Key: Any] = [
      .font: font(size: 11, weight: "Bold"), .foregroundColor: UIColor.white,
    ]
    let textSize = text.size(withAttributes: attributes)
    let badgeWidth = max(32, ceil(textSize.width) + 12)
    let stack = showCount ? CGFloat(stackIndex) * 20 : 0
    let size = CGSize(width: max(40, showCount ? badgeWidth : 40), height: 52 + stack)
    return UIGraphicsImageRenderer(size: size).image { _ in
      if showCount {
        busColor.setFill()
        UIBezierPath(roundedRect: CGRect(x: (size.width - badgeWidth)/2, y: 0, width: badgeWidth, height: 18),
                     cornerRadius: 5).fill()
        text.draw(at: CGPoint(x: (size.width - textSize.width)/2, y: 2 + (16 - textSize.height)/2),
                  withAttributes: attributes)
      }
      let pin = UIBezierPath()
      pin.move(to: CGPoint(x: 20, y: 0))
      pin.addCurve(to: CGPoint(x: 3, y: 18), controlPoint1: CGPoint(x: 10, y: 0), controlPoint2: CGPoint(x: 3, y: 8))
      pin.addCurve(to: CGPoint(x: 20, y: 44), controlPoint1: CGPoint(x: 3, y: 29), controlPoint2: CGPoint(x: 20, y: 44))
      pin.addCurve(to: CGPoint(x: 37, y: 18), controlPoint1: CGPoint(x: 20, y: 44), controlPoint2: CGPoint(x: 37, y: 29))
      pin.addCurve(to: CGPoint(x: 20, y: 0), controlPoint1: CGPoint(x: 37, y: 8), controlPoint2: CGPoint(x: 30, y: 0))
      pin.close()
      pin.apply(CGAffineTransform(a: 26/40, b: 0, c: 0, d: 32/44, tx: size.width/2 - 13, ty: 20 + stack))
      busColor.setFill(); pin.fill()
      UIColor.white.setStroke(); pin.lineWidth = 1; pin.stroke()
      UIImage(named: "bus-front")?.draw(in: CGRect(x: size.width/2 - 8, y: 25 + stack, width: 16, height: 16))
    }
  }

  static func routeArrow() -> UIImage {
    UIGraphicsImageRenderer(size: CGSize(width: 14, height: 14)).image { _ in
      let arrow = UIBezierPath()
      arrow.move(to: CGPoint(x: 4, y: 3))
      arrow.addLine(to: CGPoint(x: 9, y: 7))
      arrow.addLine(to: CGPoint(x: 4, y: 11))
      arrow.lineWidth = 2.5; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
      UIColor.white.setStroke(); arrow.stroke()
    }
  }

  static func location() -> UIImage {
    UIGraphicsImageRenderer(size: CGSize(width: 50, height: 50)).image { context in
      UIColor(red: 54/255, green: 134/255, blue: 245/255, alpha: 0.15).setFill()
      UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: 50, height: 50)).fill()
      UIColor(red: 54/255, green: 134/255, blue: 245/255, alpha: 0.25).setStroke()
      UIBezierPath(ovalIn: CGRect(x: 0.5, y: 0.5, width: 49, height: 49)).stroke()
      let dot = UIBezierPath(ovalIn: CGRect(x: 14.5, y: 14.5, width: 21, height: 21))
      context.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 5,
                                  color: UIColor(red: 22/255, green: 61/255, blue: 118/255, alpha: 0.15).cgColor)
      UIColor(red: 52/255, green: 134/255, blue: 245/255, alpha: 1).setFill(); dot.fill()
      UIColor.white.setStroke(); dot.lineWidth = 3; dot.stroke()
    }
  }
}
