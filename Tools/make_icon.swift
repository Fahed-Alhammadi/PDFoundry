// Renders the PDFoundry app icon. Usage: swift Tools/make_icon.swift <output.png>
import AppKit
import CoreGraphics

let size: CGFloat = 1024
let space = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!

func color(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: space, components: [r, g, b, a])!
}

// Background: graphite into steel.
let bg = CGGradient(colorsSpace: space, colors: [color(0.07, 0.08, 0.11), color(0.17, 0.19, 0.26)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: size), end: CGPoint(x: size, y: 0), options: [])

// Ember glow rising from the bottom, like a furnace mouth.
let glow = CGGradient(colorsSpace: space,
                      colors: [color(1.0, 0.50, 0.18, 0.95), color(0.89, 0.30, 0.10, 0.55), color(0.89, 0.30, 0.10, 0)] as CFArray,
                      locations: [0, 0.45, 1])!
ctx.drawRadialGradient(glow, startCenter: CGPoint(x: size / 2, y: -80), startRadius: 0,
                       endCenter: CGPoint(x: size / 2, y: -80), endRadius: 760, options: [])

// Document sheet with a folded corner.
let sheet = CGRect(x: 262, y: 190, width: 500, height: 650)
let fold: CGFloat = 150
let radius: CGFloat = 44
let path = CGMutablePath()
path.move(to: CGPoint(x: sheet.minX + radius, y: sheet.minY))
path.addArc(tangent1End: CGPoint(x: sheet.maxX, y: sheet.minY), tangent2End: CGPoint(x: sheet.maxX, y: sheet.maxY), radius: radius)
path.addLine(to: CGPoint(x: sheet.maxX, y: sheet.maxY - fold))
path.addLine(to: CGPoint(x: sheet.maxX - fold, y: sheet.maxY))
path.addArc(tangent1End: CGPoint(x: sheet.minX, y: sheet.maxY), tangent2End: CGPoint(x: sheet.minX, y: sheet.minY), radius: radius)
path.addArc(tangent1End: CGPoint(x: sheet.minX, y: sheet.minY), tangent2End: CGPoint(x: sheet.maxX, y: sheet.minY), radius: radius)
path.closeSubpath()

ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: -24), blur: 60, color: color(0, 0, 0, 0.45))
ctx.addPath(path)
ctx.setFillColor(color(1, 1, 1))
ctx.fillPath()
ctx.restoreGState()

// Folded corner.
let corner = CGMutablePath()
corner.move(to: CGPoint(x: sheet.maxX - fold, y: sheet.maxY))
corner.addLine(to: CGPoint(x: sheet.maxX - fold, y: sheet.maxY - fold + 30))
corner.addQuadCurve(to: CGPoint(x: sheet.maxX - fold + 30, y: sheet.maxY - fold), control: CGPoint(x: sheet.maxX - fold, y: sheet.maxY - fold))
corner.addLine(to: CGPoint(x: sheet.maxX, y: sheet.maxY - fold))
corner.closeSubpath()
ctx.addPath(corner)
ctx.setFillColor(color(0.80, 0.82, 0.87))
ctx.fillPath()

// Text lines.
ctx.setFillColor(color(0.80, 0.82, 0.87))
for (i, width) in [230.0, 340.0, 340.0, 270.0].enumerated() {
    let y = sheet.maxY - 200 - CGFloat(i) * 62
    ctx.addPath(CGPath(roundedRect: CGRect(x: sheet.minX + 80, y: y, width: width, height: 26), cornerWidth: 13, cornerHeight: 13, transform: nil))
}
ctx.fillPath()

// Signature line and an ember signature stroke.
ctx.setFillColor(color(0.80, 0.82, 0.87))
ctx.fill(CGRect(x: sheet.minX + 80, y: sheet.minY + 100, width: 340, height: 8))

let sig = CGMutablePath()
sig.move(to: CGPoint(x: sheet.minX + 90, y: sheet.minY + 150))
sig.addCurve(to: CGPoint(x: sheet.minX + 190, y: sheet.minY + 230), control1: CGPoint(x: sheet.minX + 110, y: sheet.minY + 270), control2: CGPoint(x: sheet.minX + 180, y: sheet.minY + 300))
sig.addCurve(to: CGPoint(x: sheet.minX + 230, y: sheet.minY + 150), control1: CGPoint(x: sheet.minX + 200, y: sheet.minY + 160), control2: CGPoint(x: sheet.minX + 170, y: sheet.minY + 110))
sig.addCurve(to: CGPoint(x: sheet.minX + 330, y: sheet.minY + 170), control1: CGPoint(x: sheet.minX + 280, y: sheet.minY + 230), control2: CGPoint(x: sheet.minX + 290, y: sheet.minY + 110))
sig.addCurve(to: CGPoint(x: sheet.minX + 415, y: sheet.minY + 215), control1: CGPoint(x: sheet.minX + 360, y: sheet.minY + 215), control2: CGPoint(x: sheet.minX + 380, y: sheet.minY + 150))
ctx.addPath(sig)
ctx.setStrokeColor(color(0.89, 0.32, 0.11))
ctx.setLineWidth(24)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)
ctx.strokePath()

let image = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: image)
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "icon-1024.png"
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
