import SwiftUI

/// PDFoundry's brand palette: furnace graphite and ember orange.
enum Brand {
    static let ember = Color(red: 0.886, green: 0.318, blue: 0.106)
    static let emberLight = Color(red: 1.0, green: 0.76, blue: 0.60)
    static let graphite = Color(red: 0.07, green: 0.08, blue: 0.11)
    static let steel = Color(red: 0.17, green: 0.19, blue: 0.26)
    /// A darker ember, for the Enhance tools.
    static let emberDeep = Color(red: 0.62, green: 0.22, blue: 0.09)
    /// Ember #E2511B, for drawing on pages.
    static let emberColor = UIColor(red: 0xE2 / 255, green: 0x51 / 255, blue: 0x1B / 255, alpha: 1)
}

enum AppInfo {
    static let name = "PDFoundry"
    static let repository = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry")!
    static let help = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry#readme")!
    static let feedback = URL(string: "https://github.com/Fahed-Alhammadi/PDFoundry/issues")!
    static let website = URL(string: "https://fahed-alhammadi.github.io/PDFoundry/")!
    /// The website address as shown to the user: host and path, without the scheme or a trailing slash.
    static var websiteDisplay: String {
        let path = website.path().hasSuffix("/") ? String(website.path().dropLast()) : website.path()
        return (website.host() ?? "") + path
    }
    static let privacy = URL(string: "https://fahed-alhammadi.github.io/PDFoundry/privacy.html")!
    /// The App Store ID; the "Rate" row on About uses it.
    static let appStoreID: String? = "6839300028"

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
