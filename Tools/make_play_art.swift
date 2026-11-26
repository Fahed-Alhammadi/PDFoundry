// Renders the Google Play store images.
// Usage: swift Tools/make_play_art.swift <android-screenshots-dir> <icon-1024.png> <output-dir>
//
// Google Play's specifications:
//   Icon             512 x 512 PNG
//   Feature graphic  1024 x 500, no alpha
//   Phone screenshots 9:16, the long side at most twice the short side (so 1080 x 1920, not the raw 1080 x 2400)
import AppKit
import CoreGraphics
import CoreText

let arguments = CommandLine.arguments
guard arguments.count == 4 else { fatalError("usage: make_play_art.swift <screenshots-dir> <icon-1024.png> <output-dir>") }
let shots = URL(fileURLWithPath: arguments[1])
let iconURL = URL(fileURLWithPath: arguments[2])
let output = URL(fileURLWithPath: arguments[3])
let space = CGColorSpaceCreateDeviceRGB()

func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: space, components: [r, g, b, a])!
}
let graphite = rgb(0.07, 0.08, 0.11)
let steel = rgb(0.17, 0.19, 0.26)
let lightAccent = rgb(1.0, 0.76, 0.6)
let white = rgb(1, 1, 1)

func load(_ url: URL) -> CGImage {
    guard let image = NSImage(contentsOf: url)?.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        fatalError("cannot read \(url.path)")
    }
    return image
}

/// A context without alpha (stores reject transparent images), with the brand gradient painted in.
func canvas(_ width: Int, _ height: Int) -> CGContext {
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    let gradient = CGGradient(colorsSpace: space, colors: [graphite, steel] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: CGFloat(height)), end: CGPoint(x: CGFloat(width), y: 0), options: [])
    return ctx
}

/// Draws one line of text. `x` is the centre when `centred`, else the left edge; `baseline` is measured from the bottom.
func text(_ string: String, in ctx: CGContext, size: CGFloat, weight: NSFont.Weight, color: CGColor, x: CGFloat, baseline: CGFloat, centred: Bool) {
    let attributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: size, weight: weight),
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
    ]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: attributes))
    let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
    ctx.textPosition = CGPoint(x: centred ? x - width / 2 : x, y: baseline)
    CTLineDraw(line, ctx)
}

func save(_ ctx: CGContext, _ name: String) {
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try! rep.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
    print("wrote \(name)")
}

func drawRounded(_ image: CGImage, in rect: CGRect, radius: CGFloat, ctx: CGContext) {
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 40, color: rgb(0, 0, 0, 0.45))
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil))
    ctx.setFillColor(graphite)
    ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil))
    ctx.clip()
    ctx.interpolationQuality = .high
    ctx.draw(image, in: rect)
    ctx.restoreGState()
}

try! FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let icon = load(iconURL)

// Icon, 512 x 512.
do {
    let ctx = canvas(512, 512)
    ctx.interpolationQuality = .high
    ctx.draw(icon, in: CGRect(x: 0, y: 0, width: 512, height: 512))
    save(ctx, "icon-512.png")
}

// Feature graphic, 1024 x 500.
do {
    let ctx = canvas(1024, 500)
    drawRounded(icon, in: CGRect(x: 96, y: 150, width: 200, height: 200), radius: 46, ctx: ctx)
    text("PDFoundry", in: ctx, size: 84, weight: .bold, color: white, x: 348, baseline: 252, centred: false)
    text("Read, merge, edit and sign PDFs", in: ctx, size: 35, weight: .medium, color: lightAccent, x: 352, baseline: 190, centred: false)
    save(ctx, "feature-graphic.png")
}

// Phone screenshots, 1080 x 1920, with a caption above the screen.
let captions: [(file: String, caption: String)] = [
    ("01-library", "Your documents in one place"),
    ("02-signed", "Sign and highlight"),
    ("03-merge", "Merge documents"),
    ("04-pages", "Reorder, rotate and delete pages"),
    ("05-settings", "Private: no account, no ads"),
]
for (index, item) in captions.enumerated() {
    let shot = load(shots.appendingPathComponent("\(item.file).png"))
    let ctx = canvas(1080, 1920)
    text(item.caption, in: ctx, size: 60, weight: .bold, color: white, x: 540, baseline: 1920 - 150, centred: true)
    let height: CGFloat = 1620
    let width = height * CGFloat(shot.width) / CGFloat(shot.height)
    drawRounded(shot, in: CGRect(x: (1080 - width) / 2, y: 60, width: width, height: height), radius: 40, ctx: ctx)
    save(ctx, String(format: "phone-%02d.png", index + 1))
}
