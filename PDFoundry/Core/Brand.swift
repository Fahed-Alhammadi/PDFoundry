import SwiftUI

/// PDFoundry's brand palette: furnace graphite and ember orange.
enum Brand {
    static let ember = Color(red: 0.886, green: 0.318, blue: 0.106)
    static let emberLight = Color(red: 1.0, green: 0.76, blue: 0.60)
    static let graphite = Color(red: 0.07, green: 0.08, blue: 0.11)
    static let steel = Color(red: 0.17, green: 0.19, blue: 0.26)
}

enum AppInfo {
    static let name = "PDFoundry"
    static let repository = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry")!
    static let help = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry#readme")!
    static let feedback = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry/issues")!
    static let website = URL(string: "https://fahed-alhammadi.github.io/PDFoundry/")!
    static let privacy = URL(string: "https://fahed-alhammadi.github.io/PDFoundry/privacy.html")!
    /// Set once the app has an App Store ID; the "Rate" row stays hidden until then.
    static let appStoreID: String? = nil

    static var reviewURL: URL? {
        appStoreID.flatMap { URL(string: "https://apps.apple.com/app/id\($0)?action=write-review") }
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "Version \(short) (\(build))"
    }
}

enum SettingsKey {
    static let appearance = "appearance"
    static let horizontalScroll = "readerHorizontalScroll"
    static let librarySort = "librarySort"
    static let didSeedWelcome = "didSeedWelcome"
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}
