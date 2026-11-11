import UIKit

@main
struct MapMarkerImageChecks {
  static func main() throws {
    precondition(UIImage(named: "bus-front") != nil, "Missing web SVG asset: bus-front")
    var images: [(String, UIImage)] = []
    for selected in [false, true] {
      guard let boarding = MapMarkerImages.stop(selected: selected, boardingAllowed: true),
            let passing = MapMarkerImages.stop(selected: selected, boardingAllowed: false) else {
        preconditionFailure("정류장 핀 이미지가 누락됐다")
      }
      let size: CGFloat = selected ? 44 : 36
      precondition(boarding.size == CGSize(width: size, height: size))
      precondition(passing.size == boarding.size)
      precondition(boarding.pngData() != passing.pngData(), "승차 가능 정류장과 경유 정류장은 구분된다")
      checkCenteredGlyph(boarding, rgb: selected ? (255, 255, 255) : (152, 69, 56))
      checkCenteredGlyph(passing, rgb: selected ? (255, 255, 255) : (91, 100, 114))
      images += [("boarding \(selected)", boarding), ("passing \(selected)", passing)]
    }
    let longName = String(repeating: "정류장 이름", count: 10)
    precondition(MapMarkerImages.stopName(longName, selected: false).size.width == 160)
    precondition(MapMarkerImages.stopName(longName, selected: true).size.width == 220)
    let normal = MapMarkerImages.vehicle(caption: "현재 37석", showCount: true, stackIndex: 0)
    let stacked = MapMarkerImages.vehicle(caption: "현재 0석", showCount: true, stackIndex: 2)
    let hidden = MapMarkerImages.vehicle(caption: "현재 37석", showCount: false, stackIndex: 2)
    precondition(normal.size.height == 52)
    precondition(stacked.size.height == 92)
    precondition(hidden.size == CGSize(width: 40, height: 52))
    precondition(MapMarkerImages.vehicle(caption: "현재 1234석", showCount: true, stackIndex: 0).size.width > normal.size.width)
    images += [("37 seats", normal), ("stacked 0", stacked), ("zoom < 14", hidden),
               ("unknown", MapMarkerImages.vehicle(caption: "–", showCount: true, stackIndex: 0)),
               ("selected x1.5", normal),
               ("stop name below", MapMarkerImages.stopName("잠실역.롯데월드", selected: false)),
               ("selected stop name", MapMarkerImages.stopName("잠실역.롯데월드", selected: true)),
               ("location", MapMarkerImages.location()), ("arrow", MapMarkerImages.routeArrow())]
    let gallery = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 600)).image { _ in
      UIColor(white: 0.92, alpha: 1).setFill()
      UIRectFill(CGRect(x: 0, y: 0, width: 640, height: 600))
      for (index, item) in images.enumerated() {
        let x = CGFloat(index % 4) * 160
        let y = CGFloat(index / 4) * 120
        (item.0 as NSString).draw(at: CGPoint(x: x + 8, y: y + 8),
                                 withAttributes: [.font: UIFont.systemFont(ofSize: 10), .foregroundColor: UIColor.black])
        let scale: CGFloat = item.0 == "selected x1.5" ? 1.5 : 1
        item.1.draw(in: CGRect(x: x + (160 - item.1.size.width * scale)/2, y: y + 28,
                              width: item.1.size.width * scale, height: item.1.size.height * scale))
      }
    }
    try gallery.pngData()!.write(to: URL(fileURLWithPath: "/tmp/salmonbus-map-marker-preview.png"))
    print("PASS: assets, 4 stop states, vehicle count sizing/stacking/zoom, marker preview")
  }

  private static func checkCenteredGlyph(_ image: UIImage, rgb: (Int, Int, Int)) {
    guard let source = image.cgImage, image.scale == 3,
          let context = CGContext(data: nil, width: source.width, height: source.height,
                                  bitsPerComponent: 8, bytesPerRow: source.width * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
          let pixels = context.data?.assumingMemoryBound(to: UInt8.self) else {
      preconditionFailure("정류장 핀의 3배 해상도 픽셀을 읽을 수 없다")
    }
    context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
    let centerX = source.width / 2
    let centerY = source.height / 2
    var glyphBounds = CGRect.null
    // 테두리가 들어오는 바깥쪽 픽셀을 제외하고 버스 그림의 경계를 구한다.
    for y in (centerY - 20)..<(centerY + 20) {
      for x in (centerX - 20)..<(centerX + 20) {
        let offset = y * context.bytesPerRow + x * 4
        if pixels[offset + 3] > 235,
           abs(Int(pixels[offset]) - rgb.0) < 12,
           abs(Int(pixels[offset + 1]) - rgb.1) < 12,
           abs(Int(pixels[offset + 2]) - rgb.2) < 12 {
          glyphBounds = glyphBounds.union(CGRect(x: x, y: y, width: 1, height: 1))
        }
      }
    }
    precondition(!glyphBounds.isNull && abs(glyphBounds.midX - CGFloat(centerX)) <= 1
      && abs(glyphBounds.midY - CGFloat(centerY)) <= 1, "버스 그림이 사각형의 중앙을 벗어났다")
  }
}
