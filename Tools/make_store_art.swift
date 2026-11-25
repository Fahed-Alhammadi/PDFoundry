// Renders the App Store "Product Page Header" and "Search Results" images.
// Usage: swift Tools/make_store_art.swift <screenshots-dir> <output-dir> [--guides]
//
// Apple's creative asset specifications (iOS 27):
//   Header          3840 x 1646 (21:9), PNG, no alpha. Art safe area 1646 x 661 at x 1097, y 493.
//   Search Results  3840 x 2560 (3:2), PNG or JPEG, no alpha. Art safe area 2168 x 1030 at x 836, y 765.
// Safe-area origins are measured from the top-left corner. `--guides` outlines the safe areas for checking.
import AppKit
import CoreGraphics
import CoreText

let arguments = CommandLine.arguments
guard arguments.count >= 3 else { fatalError("usage: make_store_art.swift <screenshots-dir> <output-dir> [--guides]") }
let shots = URL(fileURLWithPath: arguments[1])
let output = URL(fileURLWithPath: arguments[2])
let showGuides = arguments.contains("--guides")
let space = CGColorSpaceCreateDeviceRGB()

func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: space, components: [r, g, b, a])!
}

let graphite = rgb(0.07, 0.08, 0.11)
let steel = rgb(0.17, 0.19, 0.26)
let ember = rgb(0.886, 0.318, 0.106)
let paperGrey = rgb(0.80, 0.82, 0.87)

/// A canvas whose drawing helpers take top-left coordinates, like Apple's safe-area tables.
struct Canvas {
    let ctx: CGContext
    let width: CGFloat
    let height: CGFloat

    init(width: Int, height: Int) {
        // noneSkipLast: Apple rejects images with an alpha channel.
        ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        self.width = CGFloat(width)
        self.height = CGFloat(height)
    }

    /// Converts a top-left rectangle to Core Graphics' bottom-left space.
    func flip(_ rect: CGRect) -> CGRect {
        CGRect(x: rect.minX, y: height - rect.maxY, width: rect.width, height: rect.height)
    }

    /// The furnace: graphite into steel, with an ember glow rising from below.
    func background(glowRadius: CGFloat) {
        let base = CGGradient(colorsSpace: space, colors: [graphite, steel] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(base, start: CGPoint(x: 0, y: height), end: CGPoint(x: width, y: 0), options: [])
        let glow = CGGradient(colorsSpace: space,
                              colors: [rgb(1.0, 0.50, 0.18, 0.85), rgb(0.89, 0.30, 0.10, 0.45), rgb(0.89, 0.30, 0.10, 0)] as CFArray,
                              locations: [0, 0.45, 1])!
        let center = CGPoint(x: width / 2, y: -glowRadius * 0.28)
        ctx.drawRadialGradient(glow, startCenter: center, startRadius: 0, endCenter: center, endRadius: glowRadius, options: [])
    }

    /// The brand mark: a sheet with a folded corner and an ember signature. `rect` is top-left based.
    func sheet(in rect: CGRect, opacity: CGFloat = 1, rotation: CGFloat = 0) {
        let target = flip(rect)
        ctx.saveGState()
        ctx.setAlpha(opacity)
        ctx.translateBy(x: target.midX, y: target.midY)
        ctx.rotate(by: rotation)
        // The mark is defined on a 500 x 650 sheet.
        let scale = target.height / 650
        ctx.scaleBy(x: scale, y: scale)
        ctx.translateBy(x: -250, y: -325)

        let fold: CGFloat = 150, radius: CGFloat = 44
        let outline = CGMutablePath()
        outline.move(to: CGPoint(x: radius, y: 0))
        outline.addArc(tangent1End: CGPoint(x: 500, y: 0), tangent2End: CGPoint(x: 500, y: 650), radius: radius)
        outline.addLine(to: CGPoint(x: 500, y: 650 - fold))
        outline.addLine(to: CGPoint(x: 500 - fold, y: 650))
        outline.addArc(tangent1End: CGPoint(x: 0, y: 650), tangent2End: CGPoint(x: 0, y: 0), radius: radius)
        outline.addArc(tangent1End: CGPoint(x: 0, y: 0), tangent2End: CGPoint(x: 500, y: 0), radius: radius)
        outline.closeSubpath()

        ctx.saveGState()
        if opacity == 1 { ctx.setShadow(offset: CGSize(width: 0, height: -24), blur: 70, color: rgb(0, 0, 0, 0.5)) }
        ctx.addPath(outline)
        ctx.setFillColor(rgb(1, 1, 1))
        ctx.fillPath()
        ctx.restoreGState()

        let corner = CGMutablePath()
        corner.move(to: CGPoint(x: 500 - fold, y: 650))
        corner.addLine(to: CGPoint(x: 500 - fold, y: 650 - fold + 30))
        corner.addQuadCurve(to: CGPoint(x: 500 - fold + 30, y: 650 - fold), control: CGPoint(x: 500 - fold, y: 650 - fold))
        corner.addLine(to: CGPoint(x: 500, y: 650 - fold))
        corner.closeSubpath()
        ctx.addPath(corner)
        ctx.setFillColor(paperGrey)
        ctx.fillPath()

        ctx.setFillColor(paperGrey)
        for (index, lineWidth) in [230.0, 340.0, 340.0, 270.0].enumerated() {
            let y = 650 - 200 - CGFloat(index) * 62
            ctx.addPath(CGPath(roundedRect: CGRect(x: 80, y: y, width: lineWidth, height: 26), cornerWidth: 13, cornerHeight: 13, transform: nil))
        }
        ctx.fillPath()
        ctx.fill(CGRect(x: 80, y: 100, width: 340, height: 8))

        let signature = CGMutablePath()
        signature.move(to: CGPoint(x: 90, y: 150))
        signature.addCurve(to: CGPoint(x: 190, y: 230), control1: CGPoint(x: 110, y: 270), control2: CGPoint(x: 180, y: 300))
        signature.addCurve(to: CGPoint(x: 230, y: 150), control1: CGPoint(x: 200, y: 160), control2: CGPoint(x: 170, y: 110))
        signature.addCurve(to: CGPoint(x: 330, y: 170), control1: CGPoint(x: 280, y: 230), control2: CGPoint(x: 290, y: 110))
        signature.addCurve(to: CGPoint(x: 415, y: 215), control1: CGPoint(x: 360, y: 215), control2: CGPoint(x: 380, y: 150))
        ctx.addPath(signature)
        ctx.setStrokeColor(ember)
        ctx.setLineWidth(24)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.strokePath()
        ctx.restoreGState()
    }

    /// Draws lines of text with their top-left corner at `origin`. Returns the height used.
    @discardableResult
    func text(_ lines: [(String, CGColor)], size: CGFloat, weight: NSFont.Weight, at origin: CGPoint, lineSpacing: CGFloat = 1.08) -> CGFloat {
        let font = NSFont.systemFont(ofSize: size, weight: weight)
        var y = origin.y
        for (string, color) in lines {
            let attributed = NSAttributedString(string: string, attributes: [
                .font: font, .foregroundColor: NSColor(cgColor: color)!, .kern: -size * 0.02
            ])
            let line = CTLineCreateWithAttributedString(attributed)
            ctx.textPosition = CGPoint(x: origin.x, y: height - y - font.ascender)
            CTLineDraw(line, ctx)
            y += size * lineSpacing
        }
        return y - origin.y
    }

    /// A screenshot shown as a phone: rounded corners, a dark bezel and a soft shadow. `rect` is top-left based.
    func phone(_ name: String, in rect: CGRect) {
        guard let source = CGImageSourceCreateWithURL(shots.appendingPathComponent(name) as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fatalError("Missing screenshot \(name)") }
        let target = flip(rect)
        let radius = rect.width * 0.13
        let bezel = rect.width * 0.022
        let body = CGPath(roundedRect: target.insetBy(dx: -bezel, dy: -bezel), cornerWidth: radius + bezel, cornerHeight: radius + bezel, transform: nil)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -30), blur: 80, color: rgb(0, 0, 0, 0.55))
        ctx.addPath(body)
        ctx.setFillColor(rgb(0.02, 0.02, 0.04))
        ctx.fillPath()
        ctx.restoreGState()
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: target, cornerWidth: radius, cornerHeight: radius, transform: nil))
        ctx.clip()
        ctx.interpolationQuality = .high
        ctx.draw(image, in: target)
        ctx.restoreGState()
    }

    func guide(_ rect: CGRect) {
        guard showGuides else { return }
        ctx.setStrokeColor(rgb(0, 1, 0.4))
        ctx.setLineWidth(6)
        ctx.stroke(flip(rect))
    }

    func write(_ name: String) throws {
        let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
        try rep.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
    }
}

try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

// MARK: Product Page Header: one clear idea, a signed page.

let header = Canvas(width: 3840, height: 1646)
let headerSafe = CGRect(x: 1097, y: 493, width: 1646, height: 661)
header.background(glowRadius: 2300)
// Background art outside the safe area: faint pages drifting toward the furnace.
header.sheet(in: CGRect(x: 250, y: 420, width: 560, height: 728), opacity: 0.07, rotation: 0.22)
header.sheet(in: CGRect(x: 3020, y: 520, width: 520, height: 676), opacity: 0.07, rotation: -0.2)
header.sheet(in: CGRect(x: 2860, y: -250, width: 380, height: 494), opacity: 0.045, rotation: 0.35)
header.sheet(in: CGRect(x: 640, y: -200, width: 340, height: 442), opacity: 0.045, rotation: -0.3)
// Inside the safe area: the mark and one short phrase.
header.sheet(in: CGRect(x: headerSafe.minX + 70, y: headerSafe.minY + 40, width: 447, height: 581))
header.text([("Read, merge, edit", rgb(1, 1, 1)), ("and sign PDFs.", rgb(1.0, 0.76, 0.60))],
            size: 124, weight: .bold, at: CGPoint(x: headerSafe.minX + 610, y: headerSafe.minY + 190))
header.guide(headerSafe)
try header.write(showGuides ? "header-guides.png" : "header.png")

// MARK: Search Results: show the real interface.

let search = Canvas(width: 3840, height: 2560)
let searchSafe = CGRect(x: 836, y: 765, width: 2168, height: 1030)
search.background(glowRadius: 3000)
search.sheet(in: CGRect(x: 120, y: 1500, width: 620, height: 806), opacity: 0.06, rotation: 0.2)
search.sheet(in: CGRect(x: 3120, y: 220, width: 560, height: 728), opacity: 0.06, rotation: -0.22)
search.text([("Read, merge,", rgb(1, 1, 1)), ("edit and sign", rgb(1, 1, 1)), ("your PDFs.", rgb(1.0, 0.76, 0.60))],
            size: 96, weight: .bold, at: CGPoint(x: searchSafe.minX + 30, y: searchSafe.minY + 290))
search.text([("On your device. No account.", rgb(1, 1, 1, 0.72))],
            size: 40, weight: .medium, at: CGPoint(x: searchSafe.minX + 34, y: searchSafe.minY + 640))
// Three phones, each exactly the height of the safe area.
let phoneHeight: CGFloat = 990
let phoneWidth = phoneHeight * 1320 / 2868
let gap: CGFloat = 52
let phonesWidth = phoneWidth * 3 + gap * 2
var x = searchSafe.maxX - 30 - phonesWidth
for name in ["08-signed.png", "01-library.png", "05-pages.png"] {
    search.phone(name, in: CGRect(x: x, y: searchSafe.minY + 20, width: phoneWidth, height: phoneHeight))
    x += phoneWidth + gap
}
search.guide(searchSafe)
try search.write(showGuides ? "search-results-guides.png" : "search-results.png")
