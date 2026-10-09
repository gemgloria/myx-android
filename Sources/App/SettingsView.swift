import SwiftUI
import UniformTypeIdentifiers

struct JSONBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct SettingsView: View {
    @EnvironmentObject private var store: ScheduleStore
    @State private var editSemester: Semester?
    @State private var showPeriods = false
    @State private var deleteSemester: Semester?
    @State private var showImport = false
    @State private var showExport = false
    @State private var showBackupImport = false
    @State private var backup = JSONBackupDocument(data: Data())
    @State private var pendingBackup: ScheduleState?
    var body: some View {
        ZStack {
            AppBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("设置").font(KaiFont.system(size: 32, weight: .semibold, design: .rounded))
                    GlassSection(title: "学期") {
                        ForEach(store.state.semesters) { semester in
                            HStack(spacing: 12) {
                                Button { store.selectSemester(semester.id) } label: {
                                    HStack {
                                        Image(systemName: semester.id == store.semester.id ? "checkmark.circle.fill" : "circle")
                                            .foregroundStyle(.indigo)
                                        VStack(alignment: .leading, spacing: 5) {
                                            Text(semester.name).font(KaiFont.subheadline.weight(.medium))
                                            Text("\(semester.courses.count) 门课程 · \(semester.weekCount) 周")
                                                .font(KaiFont.caption).foregroundStyle(.secondary)
                                        }
                                        Spacer(minLength: 0)
                                    }.padding(.vertical, 8)
                                }.buttonStyle(.plain)
                                Button { editSemester = semester } label: { Image(systemName: "pencil").frame(width: 36, height: 44) }
                                    .accessibilityLabel("编辑\(semester.name)")
                                if store.state.semesters.count > 1 {
                                    Button(role: .destructive) { deleteSemester = semester } label: {
                                        Image(systemName: "trash").frame(width: 30, height: 44)
                                    }.accessibilityLabel("删除\(semester.name)")
                                }
                            }
                        }
                        Button { editSemester = .empty() } label: {
                            Label("新建学期", systemImage: "plus").frame(maxWidth: .infinity).padding(12).liquidGlass(tint: .indigo, radius: 17, interactive: true)
                        }.buttonStyle(.plain)
                    }
                    GlassSection(title: "当前学期") {
                        Button { editSemester = store.semester } label: {
                            settingRow("第1周的周一", value: store.semester.firstMonday, symbol: "calendar.badge.clock")
                        }.buttonStyle(.plain)
                        Divider()
                        Button { showPeriods = true } label: {
                            settingRow("上课时间", value: "每天\(store.semester.periods.count)节", symbol: "clock")
                        }.buttonStyle(.plain)
                    }
                    GlassSection(title: "显示") {
                        Toggle("显示周六、周日", isOn: preference(\.showWeekends)).tint(.indigo).padding(.vertical, 5)
                        Text("课表只展示当前所选周的课程。点开课程可查看同一时段的全部安排。")
                            .font(KaiFont.caption).foregroundStyle(.secondary)
                        Picker("外观", selection: preference(\.appearance)) {
                            Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark")
                        }.padding(.vertical, 5)
                    }
                    GlassSection(title: "导入与备份") {
                        Button { showImport = true } label: { settingRow("导入截图或表格", value: "", symbol: "square.and.arrow.down") }.buttonStyle(.plain)
                        Divider()
                        Button { exportBackup() } label: { settingRow(store.unreadableState ? "导出原始文件" : "导出课表备份", value: "JSON", symbol: "square.and.arrow.up") }.buttonStyle(.plain)
                        Button { showBackupImport = true } label: { settingRow("恢复课表备份", value: "", symbol: "arrow.counterclockwise") }.buttonStyle(.plain)
                    }
                    GlassSection(title: "桌面与锁屏小组件") {
                        WidgetSettingsPreview(semester: store.semester)
                        Label("今天的课程，抬眼就能看见。", systemImage: "rectangle.stack")
                        Text("长按桌面 → 编辑 → 添加小组件 → 烨昕的课表。提供下一门课、今日课表和锁屏课程。")
                            .font(KaiFont.subheadline).foregroundStyle(.secondary)
                        if !store.sharedWidgetsAvailable {
                            Text("此安装包没有启用小组件共享。请使用包含小组件、且启用了共享权限的完整签名版本。")
                                .font(KaiFont.caption).foregroundStyle(.orange)
                        }
                    }
                    Text("截图和表格均在本机识别，课表保存在本机。")
                        .font(KaiFont.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.bottom, 12)
                }.padding(22)
            }
        }.toolbar(.hidden, for: .navigationBar)
            .sheet(item: $editSemester) { value in SemesterEditorView(semester: value) { try store.saveSemester($0) } }
            .sheet(isPresented: $showPeriods) { PeriodsEditorView(semester: store.semester) { try store.saveSemester($0) } }
            .sheet(isPresented: $showImport) { ImportView() }
            .confirmationDialog("删除学期及其全部课程？", isPresented: Binding(get: { deleteSemester != nil }, set: { if !$0 { deleteSemester = nil } }), titleVisibility: .visible) {
                Button("删除学期", role: .destructive) {
                    guard let value = deleteSemester else { return }
                    store.run { try store.commit { state in
                        state.semesters.removeAll { $0.id == value.id }
                        if state.selectedSemesterID == value.id { state.selectedSemesterID = state.semesters[0].id }
                    } }
                    deleteSemester = nil
                }
            }
            .fileExporter(isPresented: $showExport, document: backup, contentType: .json, defaultFilename: "clearclass-backup") {
                if case .failure(let error) = $0 { store.message = error.localizedDescription }
            }
            .fileImporter(isPresented: $showBackupImport, allowedContentTypes: [.json]) { result in
                store.run {
                    let url = try result.get(); let granted = url.startAccessingSecurityScopedResource()
                    defer { if granted { url.stopAccessingSecurityScopedResource() } }
                    pendingBackup = try SharedRepository.decode(Data(contentsOf: url))
                }
            }
            .confirmationDialog("恢复备份将替换所有学期与课程。", isPresented: Binding(get: { pendingBackup != nil }, set: { if !$0 { pendingBackup = nil } }), titleVisibility: .visible) {
                Button("恢复备份", role: .destructive) {
                    guard let value = pendingBackup else { return }
                    store.run { try store.commit({ $0 = value }, allowRecovery: true) }
                    pendingBackup = nil
                }
            }
    }
    private func settingRow(_ title: String, value: String, symbol: String) -> some View {
        HStack(spacing: 11) {
            Image(systemName: symbol).foregroundStyle(.indigo).frame(width: 24)
            Text(title).font(KaiFont.subheadline)
            Spacer(minLength: 6)
            Text(value).font(KaiFont.caption).foregroundStyle(.secondary)
            Image(systemName: "chevron.right").font(KaiFont.caption2).foregroundStyle(.secondary)
        }.padding(.horizontal, 12).padding(.vertical, 12).liquidGlass(radius: 16, interactive: true)
    }
    private func preference<Value>(_ keyPath: WritableKeyPath<Preferences, Value>) -> Binding<Value> {
        Binding(get: { store.state.preferences[keyPath: keyPath] }, set: { value in
            store.run { try store.commit { $0.preferences[keyPath: keyPath] = value } }
        })
    }
    private func exportBackup() {
        store.run {
            let data: Data
            if store.unreadableState { data = try Data(contentsOf: SharedRepository.fileURL()) }
            else { data = try SharedRepository.encode(store.state) }
            backup = JSONBackupDocument(data: data)
            showExport = true
        }
    }
}

struct WidgetSettingsPreview: View {
    let semester: Semester
    @State private var now = AppPreview.date
    private var next: Lesson? { ScheduleEngine.nextLesson(after: now, semester: semester) }
    private var today: [Lesson] { ScheduleEngine.lessons(on: now, semester: semester) }
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text("小组件预览").font(KaiFont.subheadline)
            VStack(alignment: .leading, spacing: 10) {
                Text("烨昕的课表").font(KaiFont.caption).foregroundStyle(.secondary)
                Text(next?.course.name ?? "暂时没有课程").font(KaiFont.headline).lineLimit(3)
                Spacer(minLength: 0)
                if let lesson = next {
                    Text(lesson.start, style: .time).font(KaiFont.title2)
                    Text(lesson.course.location).font(KaiFont.caption).foregroundStyle(.secondary).lineLimit(1)
                } else { Image(systemName: "leaf").foregroundStyle(.indigo) }
            }.padding(17).frame(width: 158, height: 170, alignment: .topLeading)
                .liquidGlass(tint: next?.course.color.tint ?? .indigo, radius: 26)
            VStack(alignment: .leading, spacing: 10) {
                Text("今日课表").font(KaiFont.headline)
                if today.isEmpty { Text("今天没有课").font(KaiFont.caption).foregroundStyle(.secondary) }
                ForEach(Array(today.prefix(3))) { lesson in
                    HStack(spacing: 9) {
                        Text(lesson.start, style: .time).font(KaiFont.caption)
                        Text(lesson.course.name).font(KaiFont.subheadline).lineLimit(1)
                        Spacer(minLength: 0)
                    }.foregroundStyle(lesson.course.color.tint)
                }
            }.padding(17).frame(maxWidth: .infinity, minHeight: 130, alignment: .topLeading)
                .liquidGlass(tint: .indigo, radius: 26)
        }.onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { now = $0 }
    }
}

struct SemesterEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: Semester
    @State private var errorText: String?
    let save: (Semester) throws -> Void
    init(semester: Semester, save: @escaping (Semester) throws -> Void) { _draft = State(initialValue: semester); self.save = save }
    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(spacing: 20) {
                        GlassSection(title: "学期名称") { TextField("例如 2026–2027 第1学期", text: $draft.name).glassField() }
                        GlassSection(title: "学期日期") {
                            DatePicker("第1周的周一", selection: Binding(get: { AcademicCalendar.date(from: draft.firstMonday)! }, set: {
                                draft.firstMonday = AcademicCalendar.string(from: AcademicCalendar.monday(of: $0))
                            }), displayedComponents: .date).glassField()
                            Text("选择任意日期，会自动对齐到该周的周一。日期以你确认的校历为准。")
                                .font(KaiFont.caption).foregroundStyle(.secondary)
                            Stepper("共 \(draft.weekCount) 周", value: $draft.weekCount, in: 1...53).glassField()
                        }
                    }.padding(20)
                }
            }.navigationTitle("学期设置").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("保存") {
                        do { try draft.validate(); try save(draft); dismiss() } catch { errorText = error.localizedDescription }
                    } }
                }.alert("无法保存", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                    Button("知道了", role: .cancel) {}
                } message: { Text(errorText ?? "") }
        }
    }
}

struct PeriodsEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: Semester
    @State private var errorText: String?
    let save: (Semester) throws -> Void
    init(semester: Semester, save: @escaping (Semester) throws -> Void) { _draft = State(initialValue: semester); self.save = save }
    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(spacing: 14) {
                        Text("按学校作息修改。时间会同时用于课表和小组件。")
                            .font(KaiFont.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                        ForEach(draft.periods.indices, id: \.self) { index in
                            HStack {
                                Text("第\(index + 1)节").font(KaiFont.subheadline.weight(.medium))
                                Spacer(minLength: 8)
                                DatePicker("开始", selection: timeBinding(index, start: true), displayedComponents: .hourAndMinute).labelsHidden()
                                Text("–").foregroundStyle(.secondary)
                                DatePicker("结束", selection: timeBinding(index, start: false), displayedComponents: .hourAndMinute).labelsHidden()
                            }.padding(16).liquidGlass(radius: 20)
                        }
                        HStack {
                            Button("增加一节") {
                                guard let last = draft.periods.last, last.endMinute + 50 < 1440 else { return }
                                draft.periods.append(.init(id: draft.periods.count + 1, startMinute: last.endMinute + 5, endMinute: last.endMinute + 50))
                            }.disabled(draft.periods.count >= 16 || (draft.periods.last?.endMinute ?? 0) + 50 >= 1440)
                            Spacer()
                            Button("删除最后一节", role: .destructive) { draft.periods.removeLast() }
                                .disabled(draft.periods.count <= max(1, draft.courses.map(\.endPeriod).max() ?? 1))
                        }.font(KaiFont.subheadline).padding(14)
                    }.padding(20)
                }
            }.navigationTitle("上课时间").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("保存") {
                        do { try draft.validate(); try save(draft); dismiss() } catch { errorText = error.localizedDescription }
                    } }
                }.alert("请检查时间", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                    Button("知道了", role: .cancel) {}
                } message: { Text(errorText ?? "") }
        }
    }
    private func timeBinding(_ index: Int, start: Bool) -> Binding<Date> {
        Binding(get: { AcademicCalendar.atMinute(start ? draft.periods[index].startMinute : draft.periods[index].endMinute, on: .now) }, set: { date in
            let parts = AcademicCalendar.calendar.dateComponents([.hour, .minute], from: date)
            let value = parts.hour! * 60 + parts.minute!
            if start { draft.periods[index].startMinute = value } else { draft.periods[index].endMinute = value }
        })
    }
}
