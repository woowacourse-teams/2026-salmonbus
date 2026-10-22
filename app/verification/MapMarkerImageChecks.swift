import UIKit

@main
struct MapMarkerImageChecks {
  static func main() throws {
    for asset in ["bus-front", "stop-boarding", "stop-nonboarding"] {
      precondition(UIImage(named: asset) != nil, "Missing web SVG asset: \(asset)")
    }
    var images: [(String, UIImage)] = []
    for selected in [false, true] {
      for active in [true, false] {
        for boarding in [true, false] {
          let image = MapMarkerImages.stop(selected: selected, active: active, boardingAllowed: boarding)
          let size: CGFloat = selected ? 44 : 36
          precondition(image.size == CGSize(width: size, height: size))
          images.append(("stop \(selected) \(active) \(boarding)", image))
        }
      }
    }
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
               ("stop name below", MapMarkerImages.stopName("잠실역.롯데월드", selected: false, above: false)),
               ("stop name above", MapMarkerImages.stopName("잠실역.롯데월드", selected: true, above: true)),
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
    print("PASS: assets, 8 stop states, vehicle count sizing/stacking/zoom, marker preview")
  }
}
