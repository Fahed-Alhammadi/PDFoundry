import PhotosUI
import SwiftUI

/// Making new documents from a scan or from photos. Shared by the Documents tab's `+` menu and the Tools tab, so
/// both do exactly the same thing.
extension DocumentStore {
    @discardableResult
    func createFromScan(_ images: [UIImage]) async throws -> URL {
        try await create(from: images, named: "Scan \(Date.now.formatted(date: .abbreviated, time: .shortened))")
    }

    @discardableResult
    func createFromPhotos(_ items: [PhotosPickerItem]) async throws -> URL {
        var images: [UIImage] = []
        for item in items {
            if let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                images.append(image)
            }
        }
        return try await create(from: images, named: "Photos \(Date.now.formatted(date: .abbreviated, time: .shortened))")
    }
}
