import Observation
import PDFKit
import UIKit

/// One PDF file in the library.
nonisolated struct PDFItem: Identifiable, Hashable, Sendable {
    let url: URL
    let name: String
    let pageCount: Int
    let size: Int64
    let modified: Date
    let isLocked: Bool

    var id: URL { url }

    var detail: String {
        let pages = isLocked ? "Locked" : (pageCount == 1 ? "1 page" : "\(pageCount) pages")
        return "\(pages) · \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))"
    }
}

enum LibrarySort: String, CaseIterable, Identifiable {
    case recent, name, size

    var id: String { rawValue }

    var title: String {
        switch self {
        case .recent: "Date Modified"
        case .name: "Name"
        case .size: "Size"
        }
    }
}

/// The library: every PDF in the app's Documents folder (also visible in the Files app).
@Observable
final class DocumentStore {
    private(set) var items: [PDFItem] = []
    private(set) var isLoaded = false
    /// A document opened from outside the app, waiting to be shown by the library.
    var pendingOpen: URL?

    let directory: URL

    init(directory: URL = URL.documentsDirectory) {
        self.directory = directory
    }

    func start() async {
        seedWelcomeGuideIfNeeded()
        await refresh()
    }

    func refresh() async {
        items = await Self.scan(directory)
        isLoaded = true
    }

    func sorted(_ sort: LibrarySort, matching query: String) -> [PDFItem] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let filtered = trimmed.isEmpty ? items : items.filter { $0.name.localizedCaseInsensitiveContains(trimmed) }
        switch sort {
        case .recent: return filtered.sorted { $0.modified > $1.modified }
        case .name: return filtered.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .size: return filtered.sorted { $0.size > $1.size }
        }
    }

    // MARK: Adding

    /// Copies files into the library and returns their new locations.
    @discardableResult
    func importFiles(_ urls: [URL]) async throws -> [URL] {
        var imported: [URL] = []
        for url in urls {
            if url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL {
                imported.append(url)
                continue
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let destination = uniqueURL(named: url.deletingPathExtension().lastPathComponent)
            try FileManager.default.copyItem(at: url, to: destination)
            imported.append(destination)
        }
        await refresh()
        return imported
    }

    /// Handles a PDF handed to the app by Files, Mail, Safari or the share sheet.
    func openExternal(_ url: URL) {
        Task {
            if let first = try? await importFiles([url]).first {
                pendingOpen = first
            }
        }
    }

    @discardableResult
    func save(_ document: PDFDocument, named name: String) async throws -> URL {
        let destination = uniqueURL(named: name)
        guard document.write(to: destination) else { throw PDFoundryError.writeFailed }
        await refresh()
        return destination
    }

    @discardableResult
    func create(from images: [UIImage], named name: String) async throws -> URL {
        try await save(PDFOperations.document(from: images), named: name)
    }

    @discardableResult
    func merge(_ sources: [PDFItem], named name: String, deleteOriginals: Bool) async throws -> URL {
        let merged = try PDFOperations.merge(sources.map(\.url))
        let destination = uniqueURL(named: name)
        guard merged.write(to: destination) else { throw PDFoundryError.writeFailed }
        if deleteOriginals {
            for source in sources { try? FileManager.default.removeItem(at: source.url) }
        }
        await refresh()
        return destination
    }

    // MARK: Changing

    func rename(_ item: PDFItem, to newName: String) async throws {
        let clean = Self.sanitized(newName)
        guard !clean.isEmpty, clean != item.name else { return }
        try FileManager.default.moveItem(at: item.url, to: uniqueURL(named: clean))
        await refresh()
    }

    func duplicate(_ item: PDFItem) async throws {
        try FileManager.default.copyItem(at: item.url, to: uniqueURL(named: item.name + " copy"))
        await refresh()
    }

    func delete(_ targets: [PDFItem]) async {
        for item in targets { try? FileManager.default.removeItem(at: item.url) }
        await refresh()
    }

    // MARK: Helpers

    func uniqueURL(named rawName: String) -> URL {
        let base = Self.sanitized(rawName).isEmpty ? "Untitled" : Self.sanitized(rawName)
        var candidate = directory.appending(path: base + ".pdf")
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = directory.appending(path: "\(base) \(counter).pdf")
            counter += 1
        }
        return candidate
    }

    static func sanitized(_ name: String) -> String {
        var clean = name.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
        clean = clean.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.lowercased().hasSuffix(".pdf") { clean = String(clean.dropLast(4)) }
        while clean.hasPrefix(".") { clean.removeFirst() }
        return String(clean.prefix(120))
    }

    private func seedWelcomeGuideIfNeeded() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: SettingsKey.didSeedWelcome) else { return }
        defaults.set(true, forKey: SettingsKey.didSeedWelcome)
        try? PDFOperations.welcomeGuide().write(to: uniqueURL(named: "Welcome to PDFoundry"), options: .atomic)
    }

    @concurrent
    private nonisolated static func scan(_ directory: URL) async -> [PDFItem] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        return urls.filter { $0.pathExtension.lowercased() == "pdf" }.map { url in
            let values = try? url.resourceValues(forKeys: Set(keys))
            let document = PDFDocument(url: url)
            return PDFItem(
                url: url,
                name: url.deletingPathExtension().lastPathComponent,
                pageCount: document?.pageCount ?? 0,
                size: Int64(values?.fileSize ?? 0),
                modified: values?.contentModificationDate ?? .distantPast,
                isLocked: document?.isLocked ?? false
            )
        }
    }
}

/// Renders and caches first-page thumbnails for the library grid.
enum Thumbnailer {
    private static let cache = NSCache<NSString, UIImage>()

    static func thumbnail(for item: PDFItem, scale: CGFloat) async -> UIImage? {
        let key = "\(item.url.path)|\(item.modified.timeIntervalSince1970)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let image = await generate(item.url, scale: scale) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    @concurrent
    private nonisolated static func generate(_ url: URL, scale: CGFloat) async -> UIImage? {
        // Each call opens its own document, so rendering off the main thread is safe.
        guard let page = PDFDocument(url: url)?.page(at: 0) else { return nil }
        return page.thumbnail(of: CGSize(width: 240 * scale, height: 320 * scale), for: .cropBox)
    }
}
