import Foundation

// Debug launch arguments are used only for reproducible simulator screenshots.
enum AppPreview {
    static var enabled: Bool {
        #if DEBUG
        return CommandLine.arguments.contains("--preview-demo")
        #else
        return false
        #endif
    }
    static var date: Date {
        enabled ? AcademicCalendar.atMinute(540, on: AcademicCalendar.date(from: "2026-10-10")!) : .now
    }
    static var tab: Int {
        guard enabled else { return 0 }
        if CommandLine.arguments.contains("--preview-settings") { return 2 }
        if CommandLine.arguments.contains("--preview-today") { return 1 }
        return 0
    }
    static var showDetail: Bool { enabled && CommandLine.arguments.contains("--preview-detail") }
}
