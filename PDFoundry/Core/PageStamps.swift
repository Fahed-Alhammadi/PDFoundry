import CoreText
import PDFKit
import UIKit

/// How Page Numbers numbers the pages.
struct PageNumberStyle: Equatable {
    enum Position: CaseIterable, Hashable {
        case bottomCenter, bottomRight, bottomLeft, topCenter, topRight, topLeft

        var title: String {
            switch self {
            case .bottomCenter: "Bottom centre"
            case .bottomRight: "Bottom right"
            case .bottomLeft: "Bottom left"
            case .topCenter: "Top centre"
            case .topRight: "Top right"
            case .topLeft: "Top left"
            }
        }

        var isTop: Bool { self == .topCenter || self == .topRight || self == .topLeft }
    }

    enum Format: CaseIterable, Hashable {
        case number, pageNumber, numberOfTotal, pageNumberOfTotal

        func text(_ number: Int, of total: Int) -> String {
            switch self {
            case .number: "\(number)"
            case .pageNumber: "Page \(number)"
            case .numberOfTotal: "\(number) of \(total)"
            case .pageNumberOfTotal: "Page \(number) of \(total)"
            }
        }

        /// Shown in the sheet's picker.
        var example: String { text(1, of: 12) }
    }

    var position = Position.bottomCenter
    var format = Format.number
    var startAt = 1
    /// Leaves the first page (a cover) without a number; numbering starts on page 2 with `startAt`.
    var skipFirst = false

    /// The label for the page at `index` (zero-based) of `count`, or nil for a page left unnumbered. "of N" counts
    /// to the last number written.
    func label(forPage index: Int, count: Int) -> String? {
        let first = skipFirst ? 1 : 0
        guard index >= first, index < count else { return nil }
        let number = startAt + index - first
        return format.text(number, of: startAt + (count - first) - 1)
    }
}

/// How Watermark stamps text across the pages.
struct WatermarkStyle: Equatable {
    static let quickTexts = ["CONFIDENTIAL", "DRAFT", "COPY"]
    static let colors: [(name: String, color: UIColor)] = [("Graphite", Mark.ink), ("Ember", Brand.emberColor)]

    var text = "CONFIDENTIAL"
    var opacity: CGFloat = 0.25
    /// Bottom left to top right; otherwise straight across.
    var diagonal = true
    var color: UIColor = Mark.ink
}

/// An annotation that draws a page number or a watermark. Its picture is written into the file's appearance stream
/// when the document is saved, like the image stamps; after a reload, PDFKit draws that appearance.
nonisolated final class PageStampAnnotation: PDFAnnotation {
    private let painter: ((CGContext) -> Void)?
    /// From page space to the page as shown (crop box at the origin, rotation applied).
    private let shown: CGAffineTransform

    init(bounds: CGRect, subtype: PDFAnnotationSubtype, shown: CGAffineTransform, painter: @escaping (CGContext) -> Void) {
        self.painter = painter
        self.shown = shown
        super.init(bounds: bounds, forType: subtype, withProperties: nil)
    }

    required init?(coder: NSCoder) {
        painter = nil
        shown = .identity
        super.init(coder: coder)
    }

    override func draw(with box: PDFDisplayBox, in context: CGContext) {
        guard let painter else {
            super.draw(with: box, in: context)
            return
        }
        context.saveGState()
        // The painter draws in the page as shown; the context is in page space.
        context.concatenate(shown.inverted())
        painter(context)
        context.restoreGState()
    }
}

/// Page numbers and watermarks. PDFKit can't add to a page's own content, so on iOS they are locked annotations,
/// drawn with the same look as on the other platforms, printed, and not selectable, movable or deletable in
/// PDFoundry (Undo and Revert remove them).
enum PageStamps {
    static let font = "Helvetica"
    static let numberSize: CGFloat = 10
    static let margin: CGFloat = 36

    /// The subtype the stamps are written as. `Watermark` is the PDF's own type for them; PDFKit writes it with its
    /// appearance (checked in `PageToolsTests`).
    static let subtype = PDFAnnotationSubtype(rawValue: "Watermark")

    /// Adds the numbers. Returns what was added, page by page, for one Undo step.
    static func addNumbers(_ document: PDFDocument, style: PageNumberStyle) -> [(PDFPage, PDFAnnotation)] {
        var added: [(PDFPage, PDFAnnotation)] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index), let label = style.label(forPage: index, count: document.pageCount) else { continue }
            let line = Self.line(label, size: numberSize, color: Mark.ink)
            let width = CTLineGetTypographicBounds(line, nil, nil, nil)
            let size = PageImage.displayedSize(of: page)
            let x: CGFloat = switch style.position {
            case .bottomLeft, .topLeft: margin
            case .bottomRight, .topRight: size.width - margin - width
            case .bottomCenter, .topCenter: (size.width - width) / 2
            }
            // The text's bottom (descender) sits 36 pt from the bottom edge; at the top, its cap height 36 pt below it.
            let descent = CTFontGetDescent(CTFontCreateWithName(font as CFString, numberSize, nil))
            let ascent = CTFontGetAscent(CTFontCreateWithName(font as CFString, numberSize, nil))
            let baseline = style.position.isTop ? size.height - margin - ascent : margin + descent
            let rect = CGRect(x: x, y: baseline - descent, width: width, height: ascent + descent).insetBy(dx: -2, dy: -2)
            let annotation = stamp(on: page, shownRect: rect) { context in
                context.textPosition = CGPoint(x: x, y: baseline)
                CTLineDraw(line, context)
            }
            added.append((page, annotation))
        }
        return added
    }

    /// Adds the watermark to every page. Returns what was added, for one Undo step.
    static func addWatermark(_ document: PDFDocument, style: WatermarkStyle) -> [(PDFPage, PDFAnnotation)] {
        let text = style.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        var added: [(PDFPage, PDFAnnotation)] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let size = PageImage.displayedSize(of: page)
            // Sized so the text is about 60% of the page width.
            let probe = Self.line(text, size: 100, color: style.color)
            let probeWidth = max(1, CTLineGetTypographicBounds(probe, nil, nil, nil))
            let fontSize = 100 * size.width * 0.6 / probeWidth
            let line = Self.line(text, size: fontSize, color: style.color)
            var ascent: CGFloat = 0, descent: CGFloat = 0
            let width = CTLineGetTypographicBounds(line, &ascent, &descent, nil)
            let angle = style.diagonal ? atan2(size.height, size.width) : 0
            let centre = CGPoint(x: size.width / 2, y: size.height / 2)
            let local = CGRect(x: -width / 2, y: -(ascent - descent) / 2 - descent, width: width, height: ascent + descent)
            let turn = CGAffineTransform(translationX: centre.x, y: centre.y).rotated(by: angle)
            let rect = local.applying(turn).insetBy(dx: -4, dy: -4)
            let opacity = style.opacity
            let annotation = stamp(on: page, shownRect: rect) { context in
                context.translateBy(x: centre.x, y: centre.y)
                context.rotate(by: angle)
                context.setAlpha(opacity)
                context.textPosition = CGPoint(x: -width / 2, y: -(ascent - descent) / 2)
                CTLineDraw(line, context)
            }
            added.append((page, annotation))
        }
        return added
    }

    /// A locked stamp over `shownRect` (in the page as shown), drawn by `painter` in that same space.
    private static func stamp(on page: PDFPage, shownRect: CGRect, painter: @escaping (CGContext) -> Void) -> PDFAnnotation {
        let transform = shownTransform(of: page)
        let bounds = shownRect.applying(transform.inverted()).standardized
        let annotation = PageStampAnnotation(bounds: bounds, subtype: subtype, shown: transform, painter: painter)
        annotation.setValue(true, forAnnotationKey: Annotations.pageStampKey)
        // Printed (4), read-only (64), locked (128).
        annotation.setValue(NSNumber(value: 4 | 64 | 128), forAnnotationKey: .flags)
        page.addAnnotation(annotation)
        return annotation
    }

    /// From page space to the page as shown, with the shown crop box's lower-left corner at the origin.
    static func shownTransform(of page: PDFPage) -> CGAffineTransform {
        let transform = page.transform(for: .cropBox)
        let shown = page.bounds(for: .cropBox).applying(transform)
        return transform.concatenating(CGAffineTransform(translationX: -shown.minX, y: -shown.minY))
    }

    /// One line of text, shaped by Core Text (Arabic joins and reads right to left; fonts fall back as needed).
    private static func line(_ text: String, size: CGFloat, color: UIColor) -> CTLine {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: CTFontCreateWithName(font as CFString, size, nil),
            .foregroundColor: color.cgColor,
        ]
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
    }
}
