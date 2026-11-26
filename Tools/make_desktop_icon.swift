// Renders the desktop app icons from the 1024 px app icon.
// Usage: swift Tools/make_desktop_icon.swift <icon-1024.png> <desktop/assets>
//   icon.png   1024 px, rounded corners, edge to edge: Windows and Linux (electron-builder makes the .ico and sizes)
//   mac.png    1024 px, the macOS shape: an 824 px rounded square with a soft shadow on a transparent canvas
// mac.png is then turned into icon.icns with sips and iconutil (the steps are in desktop/README.md).
import AppKit
import CoreGraphics

let arguments = CommandLine.arguments
guard arguments.count == 3 else { fatalError("usage: make_desktop_icon.swift <icon-1024.png> <output-dir>") }
guard let source = NSImage(contentsOfFile: arguments[1])?.cgImage(forProposedRect: nil, context: nil, hints: nil) else { fatalError("cannot read \(arguments[1])") }
let output = URL(fileURLWithPath: arguments[2])
let space = CGColorSpaceCreateDeviceRGB()

func render(_ name: String, inset: CGFloat, radius: CGFloat, shadow: Bool) {
    let size: CGFloat = 1024
    let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let rect = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
    let shape = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
    if shadow {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 24, color: CGColor(colorSpace: space, components: [0, 0, 0, 0.3])!)
        ctx.addPath(shape)
        ctx.setFillColor(CGColor(colorSpace: space, components: [0.07, 0.08, 0.11, 1])!)
        ctx.fillPath()
        ctx.restoreGState()
    }
    ctx.addPath(shape)
    ctx.clip()
    ctx.interpolationQuality = .high
    ctx.draw(source, in: rect)
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try! rep.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
    print("wrote \(name)")
}

render("icon.png", inset: 0, radius: 230, shadow: false)
render("mac.png", inset: 100, radius: 185, shadow: true)
