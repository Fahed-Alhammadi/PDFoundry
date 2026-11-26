// Renders the Android adaptive icon layers. Usage: swift Tools/make_android_icon.swift <res/drawable-nodpi dir>
// Layers are 432 x 432 px (108 dp at xxxhdpi); the mark stays inside the 66 dp safe zone.
import AppKit
import CoreGraphics

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let space = CGColorSpaceCreateDeviceRGB()
func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor { CGColor(colorSpace: space, components: [r, g, b, a])! }
func canvas(alpha: Bool) -> CGContext {
    CGContext(data: nil, width: 432, height: 432, bitsPerComponent: 8, bytesPerRow: 0, space: space,
              bitmapInfo: (alpha ? CGImageAlphaInfo.premultipliedLast : CGImageAlphaInfo.noneSkipLast).rawValue)!
}
func save(_ ctx: CGContext, _ name: String) throws {
    try NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!.write(to: out.appendingPathComponent(name))
}

// Background: graphite into steel with the ember glow.
let bg = canvas(alpha: false)
bg.drawLinearGradient(CGGradient(colorsSpace: space, colors: [rgb(0.07, 0.08, 0.11), rgb(0.17, 0.19, 0.26)] as CFArray, locations: [0, 1])!,
                      start: CGPoint(x: 0, y: 432), end: CGPoint(x: 432, y: 0), options: [])
bg.drawRadialGradient(CGGradient(colorsSpace: space, colors: [rgb(1.0, 0.50, 0.18, 0.95), rgb(0.89, 0.30, 0.10, 0.55), rgb(0.89, 0.30, 0.10, 0)] as CFArray, locations: [0, 0.45, 1])!,
                      startCenter: CGPoint(x: 216, y: -34), startRadius: 0, endCenter: CGPoint(x: 216, y: -34), endRadius: 320, options: [])
try save(bg, "ic_launcher_background.png")

// Foreground: the signed page, drawn on a 500 x 650 sheet and scaled into the safe zone.
let fg = canvas(alpha: true)
let scale: CGFloat = 196 / 650
fg.translateBy(x: 216 - 250 * scale, y: 216 - 325 * scale)
fg.scaleBy(x: scale, y: scale)
let fold: CGFloat = 150, radius: CGFloat = 44
let outline = CGMutablePath()
outline.move(to: CGPoint(x: radius, y: 0))
outline.addArc(tangent1End: CGPoint(x: 500, y: 0), tangent2End: CGPoint(x: 500, y: 650), radius: radius)
outline.addLine(to: CGPoint(x: 500, y: 650 - fold))
outline.addLine(to: CGPoint(x: 500 - fold, y: 650))
outline.addArc(tangent1End: CGPoint(x: 0, y: 650), tangent2End: CGPoint(x: 0, y: 0), radius: radius)
outline.addArc(tangent1End: CGPoint(x: 0, y: 0), tangent2End: CGPoint(x: 500, y: 0), radius: radius)
outline.closeSubpath()
fg.addPath(outline); fg.setFillColor(rgb(1, 1, 1)); fg.fillPath()
let corner = CGMutablePath()
corner.move(to: CGPoint(x: 500 - fold, y: 650))
corner.addLine(to: CGPoint(x: 500 - fold, y: 650 - fold + 30))
corner.addQuadCurve(to: CGPoint(x: 500 - fold + 30, y: 650 - fold), control: CGPoint(x: 500 - fold, y: 650 - fold))
corner.addLine(to: CGPoint(x: 500, y: 650 - fold))
corner.closeSubpath()
fg.addPath(corner); fg.setFillColor(rgb(0.80, 0.82, 0.87)); fg.fillPath()
fg.setFillColor(rgb(0.80, 0.82, 0.87))
for (i, w) in [230.0, 340.0, 340.0, 270.0].enumerated() {
    fg.addPath(CGPath(roundedRect: CGRect(x: 80, y: 650 - 200 - CGFloat(i) * 62, width: w, height: 26), cornerWidth: 13, cornerHeight: 13, transform: nil))
}
fg.fillPath()
fg.fill(CGRect(x: 80, y: 100, width: 340, height: 8))
let sig = CGMutablePath()
sig.move(to: CGPoint(x: 90, y: 150))
sig.addCurve(to: CGPoint(x: 190, y: 230), control1: CGPoint(x: 110, y: 270), control2: CGPoint(x: 180, y: 300))
sig.addCurve(to: CGPoint(x: 230, y: 150), control1: CGPoint(x: 200, y: 160), control2: CGPoint(x: 170, y: 110))
sig.addCurve(to: CGPoint(x: 330, y: 170), control1: CGPoint(x: 280, y: 230), control2: CGPoint(x: 290, y: 110))
sig.addCurve(to: CGPoint(x: 415, y: 215), control1: CGPoint(x: 360, y: 215), control2: CGPoint(x: 380, y: 150))
fg.addPath(sig); fg.setStrokeColor(rgb(0.886, 0.318, 0.106)); fg.setLineWidth(24); fg.setLineCap(.round); fg.setLineJoin(.round); fg.strokePath()
try save(fg, "ic_launcher_foreground.png")
