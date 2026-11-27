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

    /// Mends checkboxes and radio buttons saved by PDFKit (by PDFoundry, or by Preview).
    ///
    /// When PDFKit writes a button it has changed, it keeps the value (`/V`) but not the button's own "on" name: read
    /// back, the button is named "Yes" (tried: setting `/AS` and `/V` directly loses the name the same way). So a box
    /// ticked as "On" reads as unticked, and the buttons of a radio group, all named "Yes", would turn on and off
    /// together. This only touches buttons in that state, in memory; forms as they came are left alone:
    /// - a checkbox named "Yes" whose saved value is another name gets that name back (each of its widgets alike);
    /// - a radio group whose buttons are all named "Yes", with exactly one button saved as on, gives that button the
    ///   saved value as its name, and the other buttons, whose names are lost, "Choice N" (one keeps "Yes" when
    ///   the value isn't "Yes", so a Yes/No pair stays Yes/No).
    /// Fields without a name are skipped.
    static func repair(_ document: PDFDocument) {
        func saved(_ widget: PDFAnnotation) -> String? {
            let value = (widget.value(forAnnotationKey: .widgetValue) as? String) ?? ""
            return value.isEmpty || value == "Off" ? nil : value
        }
        let buttons = widgets(in: document).filter { !($0.fieldName ?? "").isEmpty && $0.widgetFieldType == .button }
        for box in buttons where box.widgetControlType == .checkBoxControl {
            if box.buttonWidgetStateString == "Yes", box.buttonWidgetState == .offState, let value = saved(box), value != "Yes" {
                box.buttonWidgetStateString = value
            }
        }
        let radios = buttons.filter { $0.widgetControlType == .radioButtonControl }
        for (_, group) in Dictionary(grouping: radios, by: { $0.fieldName ?? "" }) where group.count > 1 {
            guard group.allSatisfy({ $0.buttonWidgetStateString == "Yes" }) else { continue }
            let on = group.filter { saved($0) != nil }
            // Each button carries its own value only when all of them were written; otherwise it can't be told which.
            guard on.count == 1, let chosen = on.first, let value = saved(chosen) else { continue }
            chosen.buttonWidgetStateString = value
            chosen.buttonWidgetState = .onState
            var keptYes = value == "Yes"
            for (index, widget) in group.enumerated() where widget !== chosen {
                if !keptYes {
                    keptYes = true // still named "Yes": one other button may well be the "Yes" one
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
