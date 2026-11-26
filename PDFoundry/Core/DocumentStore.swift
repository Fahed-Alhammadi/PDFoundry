import Observation
import PDFKit
import UIKit

/// One PDF shown in the library: a document kept in PDFoundry, or a file opened where it lives.
nonisolated struct PDFItem: Identifiable, Hashable, Sendable {
    let url: URL
    let name: String
    let pageCount: Int
    let size: Int64
    let modified: Date
    let isLocked: Bool
    /// Where the file lives when it is not in PDFoundry's own folder, for example "iCloud Drive › Contracts".
    /// Nil for documents kept in PDFoundry.
    var location: String?
    /// The file is in iCloud and not on this device yet. It downloads when it is opened.
    var needsDownload = false

    var id: URL { url }

    /// Opened in place from the Files app, not copied into PDFoundry.
    var isExternal: Bool { location != nil }

    var detail: String {
        if needsDownload { return "In iCloud" }
        let pages = isLocked ? "Locked" : (pageCount == 1 ? "1 page" : "\(pageCount) pages")
        return "\(pages) · \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))"
    }
}

/// Keeps a file from the Files app readable and writable for as long as this object is alive.
nonisolated final class FileAccess: Sendable {
    private let url: URL
    private let granted: Bool

    init(_ url: URL) {
        self.url = url
        granted = url.startAccessingSecurityScopedResource()
    }

    deinit {
        if granted { url.stopAccessingSecurityScopedResource() }
    }

    /// Runs `work` with access to `url`.
    static func with<T>(_ url: URL, _ work: () throws -> T) rethrows -> T {
        let access = FileAccess(url)
        defer { withExtendedLifetime(access) {} }
        return try work()
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

/// The library: every PDF in the app's Documents folder (also visible in the Files app), plus files that were
/// opened where they live (iCloud Drive, another folder) and are edited in place without being copied.
@Observable
final class DocumentStore {
    private(set) var items: [PDFItem] = []
    private(set) var isLoaded = false
    /// A document opened from outside the app, waiting to be shown by the library.
    var pendingOpen: URL?

    let directory: URL
    /// Remembers the files opened in place, as bookmarks that keep working after a restart.
    private let linksFile: URL
    /// Files under these folders are temporary hand-overs from other apps (Mail, Safari, the share sheet).
    /// They have no lasting home, so they are copied into the library instead of being opened in place.
    private let copyRoots: [URL]

    init(directory: URL = URL.documentsDirectory, linksFile: URL? = nil, copyRoots: [URL]? = nil) {
        self.directory = directory
        self.linksFile = linksFile ?? URL.applicationSupportDirectory.appending(path: "OpenedFiles.json")
        self.copyRoots = copyRoots ?? [URL(filePath: NSHomeDirectory())]
    }

    func start() async {
        seedWelcomeGuideIfNeeded()
        await refresh()
    }

    func refresh() async {
        let kept = await Self.scan(directory)
        let (linked, dead) = await Self.resolve(links)
        // Files deleted or moved away drop off the list. Only those exact bookmarks are removed: another file may
        // have been opened while this was working, and it must not be lost.
        if !dead.isEmpty { links = links.filter { !dead.contains($0.bookmark) } }
        let own = Set(kept.map { $0.url.standardizedFileURL })
        items = kept + linked.filter { !own.contains($0.url.standardizedFileURL) }
        isLoaded = true
    }

    // MARK: Files opened in place

    fileprivate struct Link: Codable, Sendable {
        var bookmark: Data
        var added: Date
    }

    private var links: [Link] {
        get { (try? JSONDecoder().decode([Link].self, from: Data(contentsOf: linksFile))) ?? [] }
        set {
            try? FileManager.default.createDirectory(at: linksFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? JSONEncoder().encode(newValue).write(to: linksFile, options: .atomic)
        }
    }

    /// Is this a temporary hand-over that must be copied, not a file with a lasting home?
    private func mustCopy(_ url: URL) -> Bool {
        let path = url.standardizedFileURL.path
        return copyRoots.contains { path.hasPrefix($0.standardizedFileURL.path + "/") }
    }

    private func isInLibrary(_ url: URL) -> Bool {
        url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL
    }

    /// Opens files from the Files app. A file with a lasting home is opened where it is and listed with its
    /// location; nothing is copied. Temporary hand-overs are copied into the library. Returns where each file is.
    @discardableResult
    func open(_ urls: [URL]) async throws -> [URL] {
        var opened: [URL] = []
        let known = Set(await Self.resolve(links).0.map { $0.url.standardizedFileURL })
        var added: [Link] = []
        for url in urls {
            if isInLibrary(url) {
                opened.append(url)
            } else if mustCopy(url) {
                opened += try await importFiles([url])
            } else {
                try FileAccess.with(url) {
                    guard Self.isPDF(url) else { throw PDFoundryError.unreadable(url.deletingPathExtension().lastPathComponent) }
                    if !known.contains(url.standardizedFileURL) {
                        added.append(Link(bookmark: try url.bookmarkData(), added: .now))
                    }
                }
                opened.append(url)
            }
        }
        if !added.isEmpty { links += added } // read again here, so nothing saved meanwhile is overwritten
        await refresh()
        return opened
    }

    /// Takes files opened in place off the list. The files themselves are not touched.
    func forget(_ targets: [PDFItem]) async {
        let gone = Set(targets.filter(\.isExternal).map { $0.url.standardizedFileURL })
        guard !gone.isEmpty else { return }
        links = links.filter { link in
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: link.bookmark, bookmarkDataIsStale: &stale) else { return false }
            return !gone.contains(url.standardizedFileURL)
        }
        await refresh()
    }

    /// Copies a file opened in place into PDFoundry's own folder.
    @discardableResult
    func saveCopy(of item: PDFItem) async throws -> URL {
        let destination = uniqueURL(named: item.name)
        try FileAccess.with(item.url) { try FileManager.default.copyItem(at: item.url, to: destination) }
        await refresh()
        return destination
    }

    private nonisolated static func isPDF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return url.pathExtension.lowercased() == "pdf" }
        defer { try? handle.close() }
        let head = (try? handle.read(upToCount: 1024)) ?? Data()
        // A file still in iCloud has no bytes here yet: trust its name.
        return head.isEmpty ? url.pathExtension.lowercased() == "pdf" : head.range(of: Data("%PDF-".utf8)) != nil
    }

    /// A short, readable place name for a file outside PDFoundry: "iCloud Drive › Contracts".
    nonisolated static func location(of url: URL) -> String {
        let folder = url.deletingLastPathComponent()
        let parts = folder.pathComponents
        var root = "Files"
        var rest: [String] = []
        if let index = parts.firstIndex(where: { $0.hasPrefix("com~apple~CloudDocs") }) {
            root = "iCloud Drive"
            rest = Array(parts[(index + 1)...])
        } else if let index = parts.firstIndex(of: "Mobile Documents") {
            root = "iCloud Drive"
            rest = Array(parts[(index + 1)...]).filter { $0 != "Documents" && !$0.contains("~") }
        } else if let index = parts.firstIndex(of: "File Provider Storage") {
            root = "On My \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")"
            rest = Array(parts[(index + 1)...])
        } else if let last = parts.last, last != "/" {
            rest = [last]
        }
        guard let last = rest.last else { return root }
        return "\(root) › \(last)"
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

    /// Brings files into the library and returns where each one is.
    /// A file whose contents are already in the library is not copied again: its existing document is returned.
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
            if let existing = existingCopy(of: url) {
                if !imported.contains(existing) { imported.append(existing) }
                continue
            }
            let destination = uniqueURL(named: url.deletingPathExtension().lastPathComponent)
            try FileManager.default.copyItem(at: url, to: destination)
            imported.append(destination)
        }
        await refresh()
        return imported
    }

    /// A document already in the library with exactly the same contents as `url`, if there is one.
    /// Files handed over by other apps arrive as temporary copies, so contents are the only thing to compare.
    func existingCopy(of url: URL) -> URL? {
        let manager = FileManager.default
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
              let files = try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        else { return nil }
        return files.first { candidate in
            candidate.pathExtension.lowercased() == "pdf"
                && (try? candidate.resourceValues(forKeys: [.fileSizeKey]))?.fileSize == size
                && manager.contentsEqual(atPath: candidate.path, andPath: url.path)
        }
    }

    /// Handles a PDF handed to the app by Files, Mail, Safari or the share sheet.
    func openExternal(_ url: URL) {
        Task {
            if let first = try? await open([url]).first {
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
        let access = sources.map { FileAccess($0.url) }
        let merged = try withExtendedLifetime(access) { try PDFOperations.merge(sources.map(\.url)) }
        let destination = uniqueURL(named: name)
        guard merged.write(to: destination) else { throw PDFoundryError.writeFailed }
        if deleteOriginals {
            // Only documents kept in PDFoundry are deleted. Files opened in place are just taken off the list.
            for source in sources where !source.isExternal { try? FileManager.default.removeItem(at: source.url) }
            await forget(sources)
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

    /// Deletes documents kept in PDFoundry. Files opened in place are only taken off the list.
    func delete(_ targets: [PDFItem]) async {
        for item in targets where !item.isExternal { try? FileManager.default.removeItem(at: item.url) }
        await forget(targets)
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

extension DocumentStore {
    /// Turns the saved bookmarks back into files. Returns the items to show, and the bookmarks whose file is gone
    /// (deleted or moved away) so they can be dropped.
    @concurrent
    fileprivate nonisolated static func resolve(_ links: [Link]) async -> ([PDFItem], Set<Data>) {
        var items: [PDFItem] = []
        var dead: Set<Data> = []
        var seen: Set<URL> = []
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey, .ubiquitousItemDownloadingStatusKey]
        for link in links {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: link.bookmark, bookmarkDataIsStale: &stale) else {
                dead.insert(link.bookmark)
                continue
            }
            let kept: Bool = FileAccess.with(url) {
                guard (try? url.checkResourceIsReachable()) == true, seen.insert(url.standardizedFileURL).inserted else { return false }
                let values = try? url.resourceValues(forKeys: keys)
                let waiting = values?.ubiquitousItemDownloadingStatus.map { $0 != .current } ?? false
                let document = waiting ? nil : PDFDocument(url: url)
                items.append(PDFItem(
                    url: url,
                    name: url.deletingPathExtension().lastPathComponent,
                    pageCount: document?.pageCount ?? 0,
                    size: Int64(values?.fileSize ?? 0),
                    modified: max(values?.contentModificationDate ?? .distantPast, link.added),
                    isLocked: document?.isLocked ?? false,
                    location: location(of: url),
                    needsDownload: waiting
                ))
                return true
            }
            if !kept { dead.insert(link.bookmark) }
        }
        return (items, dead)
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
        FileAccess.with(url) {
            guard let page = PDFDocument(url: url)?.page(at: 0) else { return nil }
            return page.thumbnail(of: CGSize(width: 240 * scale, height: 320 * scale), for: .cropBox)
        }
    }
}
