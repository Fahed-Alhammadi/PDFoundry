import PDFKit

/// A form field's value, as the reader reads and sets it.
nonisolated enum FieldValue: Equatable {
    /// A text field (single or multi-line).
    case text(String)
    /// A checkbox or one radio button: ticked or not.
    case on(Bool)
    /// A dropdown or list: the chosen option.
    case choice(String)
}

/// Reads and fills the form fields a PDF already has (its widget annotations). Values are set on the fields
/// themselves, so PDFKit saves them as field values with appearances: nothing is flattened into the page, and
/// other apps can still read and change them.
enum FormFields {
    /// Every widget of the document, page by page, that holds a value (not push buttons or signature fields).
    static func widgets(in document: PDFDocument) -> [PDFAnnotation] {
        (0..<document.pageCount).flatMap { index in
            document.page(at: index)?.annotations.filter { value(of: $0) != nil } ?? []
        }
    }

    /// The value of a widget, or nil for an annotation that isn't a fillable field.
    static func value(of widget: PDFAnnotation) -> FieldValue? {
        guard widget.type == "Widget" else { return nil }
        switch widget.widgetFieldType {
        case .text:
            return .text(widget.widgetStringValue ?? "")
        case .button:
            switch widget.widgetControlType {
            case .checkBoxControl, .radioButtonControl: return .on(widget.buttonWidgetState == .onState)
            default: return nil
            }
        case .choice:
            return .choice(widget.widgetStringValue ?? "")
        default:
            return nil
        }
    }

    static func isSignatureField(_ annotation: PDFAnnotation) -> Bool {
        annotation.type == "Widget" && annotation.widgetFieldType == .signature
    }

    /// Sets a widget's value. Returns false, and changes nothing, for a read-only field or a value of the wrong kind.
    /// Turning a radio button on turns the other buttons of its group off.
    @discardableResult
    static func set(_ widget: PDFAnnotation, value: FieldValue) -> Bool {
        guard !widget.isReadOnly, let current = Self.value(of: widget) else { return false }
        switch (current, value) {
        case (.text, .text(let text)), (.choice, .choice(let text)):
            widget.widgetStringValue = text
        case (.on, .on(let on)):
            // The others first: buttons of one group may share the field's value, so turning one off afterwards
            // could turn this one off again.
            if on, widget.widgetControlType == .radioButtonControl {
                for other in group(of: widget) where other !== widget {
                    other.buttonWidgetState = .offState
                }
            }
            widget.buttonWidgetState = on ? .onState : .offState
        default:
            return false
        }
        return true
    }

    /// Mends checkboxes and radio buttons saved by PDFKit (by PDFoundry, or by Preview). PDFKit writes a changed
    /// button with its value as text and a single appearance, so on reading it back every button's "on" name is
    /// the default "Yes": a box ticked as "On" reads as unticked, and the buttons of a radio group all share one
    /// name, so they would all turn on and off together. The saved value still says which one is on, so each
    /// button gets a name of its own again: the value for the one that is on, "Choice N" for the others (their
    /// original names were not kept in the file). Only in memory; saved with the next change.
    static func repair(_ document: PDFDocument) {
        let buttons = widgets(in: document).filter {
            $0.widgetFieldType == .button && [.checkBoxControl, .radioButtonControl].contains($0.widgetControlType)
        }
        for (_, group) in Dictionary(grouping: buttons, by: { $0.fieldName ?? "" }) {
            let names = group.map { $0.buttonWidgetStateString }
            let shared = Set(names).count < names.count
            guard group.count == 1 || shared else { continue }
            for (index, widget) in group.enumerated() {
                let saved = (widget.value(forAnnotationKey: .widgetValue) as? String) ?? ""
                let isOn = !saved.isEmpty && saved != "Off" && widget.buttonWidgetState == .offState
                if group.count == 1 {
                    // A checkbox ticked under another name than the one PDFKit assumes.
                    if isOn, saved != widget.buttonWidgetStateString { widget.buttonWidgetStateString = saved }
                } else if isOn && !names.contains(saved) {
                    widget.buttonWidgetStateString = saved
                    widget.buttonWidgetState = .onState
                } else {
                    widget.buttonWidgetStateString = "Choice \(index + 1)"
                }
            }
        }
    }

    /// The other widgets of the same field (radio buttons share one field name).
    private static func group(of widget: PDFAnnotation) -> [PDFAnnotation] {
        guard let name = widget.fieldName, let document = widget.page?.document else { return [widget] }
        return (0..<document.pageCount).flatMap { index in
            document.page(at: index)?.annotations.filter { $0.type == "Widget" && $0.fieldName == name } ?? []
        }
    }

    /// Where a signature of `aspect` (width / height, as it looks upright) fits inside a field: as large as it can
    /// be with its shape kept, centred. In page space; a rotated page shows the field turned, so the shape is too.
    static func fit(aspect: CGFloat, in field: CGRect, rotation: Int) -> CGRect {
        guard aspect > 0, !field.isEmpty else { return field }
        let turned = ((rotation % 360) + 360) % 360 == 90 || ((rotation % 360) + 360) % 360 == 270
        let shape = turned ? 1 / aspect : aspect
        var size = CGSize(width: field.width, height: field.width / shape)
        if size.height > field.height { size = CGSize(width: field.height * shape, height: field.height) }
        return CGRect(x: field.midX - size.width / 2, y: field.midY - size.height / 2, width: size.width, height: size.height)
    }
}
