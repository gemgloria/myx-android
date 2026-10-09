import SwiftUI
import WidgetKit

@MainActor
final class ScheduleStore: ObservableObject {
    @Published private(set) var state: ScheduleState
    @Published var message: String?
    let sharedWidgetsAvailable = SharedRepository.sharedContainerAvailable
    private(set) var unreadableState = false

    init() {
        if AppPreview.enabled {
            let example = ExampleSchedule.semester()
            state = .init(selectedSemesterID: example.id, semesters: [example])
            return
        }
        do { state = try SharedRepository.load() ?? .empty() }
        catch {
            if let backup = try? SharedRepository.loadBackup() {
                state = backup
                message = "已从上一份本地备份恢复课表。"
            } else {
                state = .empty()
                unreadableState = true
                message = "已有课表文件无法读取。原文件已保留，请先导出原文件或导入有效备份。"
            }
        }
    }
    var semester: Semester { state.selectedSemester! }
    var appearance: ColorScheme? {
        state.preferences.appearance == "dark" ? .dark : state.preferences.appearance == "light" ? .light : nil
    }

    func commit(_ change: (inout ScheduleState) throws -> Void, allowRecovery: Bool = false) throws {
        if unreadableState && !allowRecovery { throw ScheduleError.invalid("已有课表无法读取。请先导入备份，防止覆盖原文件。") }
        var copy = state
        try change(&copy)
        try copy.validate()
        if unreadableState {
            let url = try SharedRepository.fileURL()
            let recovery = url.deletingPathExtension().appendingPathExtension("unreadable-\(Int(Date.now.timeIntervalSince1970)).json")
            try FileManager.default.copyItem(at: url, to: recovery)
        }
        try SharedRepository.save(copy)
        state = copy
        unreadableState = false
        WidgetCenter.shared.reloadAllTimelines()
    }
    func run(_ action: () throws -> Void) {
        do { try action() } catch { message = error.localizedDescription }
    }
    func selectSemester(_ id: UUID) {
        run { try commit { $0.selectedSemesterID = id } }
    }
    func saveSemester(_ semester: Semester) throws {
        try commit { state in
            if let index = state.semesters.firstIndex(where: { $0.id == semester.id }) { state.semesters[index] = semester }
            else { state.semesters.append(semester) }
            state.selectedSemesterID = semester.id
        }
    }
    func saveCourse(_ course: Course) throws {
        try commit { state in
            let index = state.semesters.firstIndex { $0.id == state.selectedSemesterID }!
            if let ci = state.semesters[index].courses.firstIndex(where: { $0.id == course.id }) {
                state.semesters[index].courses[ci] = course
            } else { state.semesters[index].courses.append(course) }
        }
    }
    func deleteCourse(_ id: UUID) {
        run { try commit { state in
            let index = state.semesters.firstIndex { $0.id == state.selectedSemesterID }!
            state.semesters[index].courses.removeAll { $0.id == id }
        } }
    }
    func importCourses(_ courses: [Course], to id: UUID, replace: Bool) throws -> Int {
        var added = 0
        try commit { state in
            guard let index = state.semesters.firstIndex(where: { $0.id == id }) else { throw ScheduleError.invalid("学期已被删除。") }
            if replace { state.semesters[index].courses = [] }
            var fingerprints = Set(state.semesters[index].courses.map(\.fingerprint))
            for course in courses where fingerprints.insert(course.fingerprint).inserted {
                try course.validate(maxWeeks: state.semesters[index].weekCount, periodCount: state.semesters[index].periods.count)
                state.semesters[index].courses.append(course)
                added += 1
            }
            state.selectedSemesterID = id
        }
        return added
    }
    func loadExample() {
        run { try saveSemester(ExampleSchedule.semester()) }
    }
}
