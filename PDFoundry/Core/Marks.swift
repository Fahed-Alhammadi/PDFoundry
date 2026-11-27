import UIKit

/// The shapes of Markup › Shapes.
enum ShapeKind: CaseIterable {
    case rectangle, ellipse, line, arrow

    var title: String {
        switch self {
        case .rectangle: "Rectangle"
        case .ellipse: "Ellipse"
        case .line: "Line"
        case .arrow: "Arrow"
        }
    }

    var symbol: String {
        switch self {
        case .rectangle: "rectangle"
        case .ellipse: "circle"
        case .line: "line.diagonal"
        case .arrow: "arrow.up.right"
        }
    }

    /// The stroke colours: graphite ink (the default) and the four highlight colours, solid so a 2 pt line shows.
    static let colors: [(name: String, color: UIColor)] = [
        ("Graphite", Mark.ink),
        ("Yellow", light(.systemYellow)),
        ("Green", light(.systemGreen)),
        ("Pink", light(.systemPink)),
        ("Blue", light(.systemBlue)),
    ]

    /// The colour as it is in Light Mode, so a mark looks the same whatever the device's appearance.
    private static func light(_ color: UIColor) -> UIColor {
        color.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light))
    }

    static let lineWidth: CGFloat = 2
}

/// The Check (✓) and Cross (✗) marks of Fill & Sign.
enum Mark: CaseIterable {
    case check, cross

    /// The dark ink of text on the page, the same in Light and Dark Mode (graphite #12141C).
    static let ink = UIColor(red: 0x12 / 255, green: 0x14 / 255, blue: 0x1C / 255, alpha: 1)

    var title: String {
        switch self {
        case .check: "Check"
        case .cross: "Cross"
        }
    }

    var symbol: String {
        switch self {
        case .check: "checkmark"
        case .cross: "xmark"
        }
    }

    /// The mark as a transparent image whose longer side is `side` pixels, drawn from the SF Symbol's vector
    /// outline so it stays sharp when the page is zoomed.
    func image(side: CGFloat = 512) -> UIImage {
        let configuration = UIImage.SymbolConfiguration(pointSize: side, weight: .bold)
        guard let symbol = UIImage(systemName: symbol, withConfiguration: configuration)?
            .withTintColor(Self.ink, renderingMode: .alwaysOriginal) else { return UIImage() }
        let size = symbol.size
        let scale = side / max(size.width, size.height, 1)
        let target = CGSize(width: (size.width * scale).rounded(.up), height: (size.height * scale).rounded(.up))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            symbol.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
