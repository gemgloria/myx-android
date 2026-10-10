import SwiftUI

struct CourseEditorView: View {
    let semester: Semester
    let save: (Course) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft: Course
    @State private var weeksText: String
    @State private var errorText: String?

    init(semester: Semester, course: Course? = nil, save: @escaping (Course) throws -> Void) {
        self.semester = semester; self.save = save
        let value = course ?? Course(name: "", weekday: 1, startPeriod: 1, endPeriod: min(2, semester.periods.count),
                                     weeks: Array(1...min(18, semester.weekCount)))
        _draft = State(initialValue: value)
        _weeksText = State(initialValue: value.weekText)
    }
    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(spacing: 20) {
                        GlassSection(title: "课程信息") {
                            TextField("课程名称", text: $draft.name).glassField()
                            TextField("任课教师（选填）", text: $draft.teacher).glassField()
                            TextField("上课教室（选填）", text: $draft.location).glassField()
                        }
                        GlassSection(title: "上课安排") {
                            Picker("星期", selection: $draft.weekday) {
                                ForEach(1...7, id: \.self) { Text(AcademicCalendar.weekdayNames[$0 - 1]).tag($0) }
                            }.glassField()
                            HStack {
                                Picker("开始", selection: $draft.startPeriod) {
                                    ForEach(semester.periods) { Text("第\($0.id)节").tag($0.id) }
                                }
                                Spacer()
                                Text("至").foregroundStyle(.secondary)
                                Spacer()
                                Picker("结束", selection: $draft.endPeriod) {
                                    ForEach(semester.periods.filter { $0.id >= draft.startPeriod }) { Text("第\($0.id)节").tag($0.id) }
                                }
                            }.glassField()
                            TextField("周次，如 1-4,6-14", text: $weeksText).textInputAutocapitalization(.never).glassField()
                            Text("支持连续周、间隔周和单双周，例如 1-18单周。")
                                .font(KaiFont.caption).foregroundStyle(.secondary)
                            HStack(spacing: 12) {
                                Button("全部周") { weeksText = "1-\(semester.weekCount)" }
                                Button("单周") { weeksText = "1-\(semester.weekCount)单周" }
                                Button("双周") { weeksText = "1-\(semester.weekCount)双周" }
                            }.font(KaiFont.subheadline).padding(.vertical, 7)
                        }
                        GlassSection(title: "课程颜色") {
                            HStack(spacing: 6) {
                                ForEach(CourseColor.allCases, id: \.self) { color in
                                    Button { draft.color = color } label: {
                                        ZStack {
                                            Circle().fill(color.tint.opacity(0.16)).frame(width: 37, height: 37)
                                            if color == draft.color { Image(systemName: "checkmark").foregroundStyle(color.tint) }
                                        }.frame(maxWidth: .infinity, minHeight: 44).liquidGlass(tint: color.tint, radius: 22, interactive: true)
                                    }.buttonStyle(.plain).accessibilityLabel(color.rawValue)
                                        .accessibilityAddTraits(color == draft.color ? .isSelected : [])
                                }
                            }
                        }
                        GlassSection(title: "备注") { TextField("选填", text: $draft.note, axis: .vertical).lineLimit(2...5).glassField() }
                        if !ScheduleEngine.conflicts(for: draft, in: semester).isEmpty {
                            Label("与现有课程的部分周次重叠，请核对后保存。", systemImage: "exclamationmark.circle")
                                .font(KaiFont.subheadline).foregroundStyle(.orange).padding(16).liquidGlass(tint: .orange)
                        }
                    }.padding(20)
                }
            }.navigationTitle(draft.name.isEmpty ? "添加课程" : "编辑课程").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("保存") { persist() }.fontWeight(.semibold) }
                }
                .onChange(of: draft.startPeriod) { _, value in if draft.endPeriod < value { draft.endPeriod = value } }
                .alert("请检查课程", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                    Button("知道了", role: .cancel) {}
                } message: { Text(errorText ?? "") }
        }
    }
    private func persist() {
        do {
            var value = draft
            value.name = value.name.trimmingCharacters(in: .whitespacesAndNewlines)
            value.weeks = try WeekExpression.parse(weeksText, maxWeek: semester.weekCount)
            try value.validate(maxWeeks: semester.weekCount, periodCount: semester.periods.count)
            try save(value)
            dismiss()
        } catch { errorText = error.localizedDescription }
    }
}

struct CourseDetailView: View {
    @EnvironmentObject private var store: ScheduleStore
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    let course: Course
    let week: Int
    let close: () -> Void
    @State private var editing: Course?
    @State private var deleting: Course?
    private var current: Course { store.semester.courses.first { $0.id == course.id } ?? course }
    private var alternatives: [Course] {
        CourseLayout.alternatives(for: current, courses: store.semester.courses, week: week)
    }
    var body: some View {
        ZStack {
            if reduceTransparency { Color(uiColor: .systemBackground).ignoresSafeArea().onTapGesture(perform: close) }
            else { Rectangle().fill(.ultraThinMaterial).ignoresSafeArea().onTapGesture(perform: close) }
            VStack(spacing: 20) {
                HStack {
                    Text("第 \(week) 周 · 同一时段").font(KaiFont.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    GlassIconButton(symbol: "xmark", label: "关闭课程详情", action: close)
                        .accessibilityIdentifier("close-course")
                }.padding(.horizontal, 22).padding(.top, 16)
                Spacer(minLength: 0)
                ScrollView {
                    GlassGroup {
                        VStack(spacing: 18) {
                            ForEach(alternatives) { arrangement in detailCard(arrangement) }
                            if alternatives.filter({ $0.isActive(week: week) }).count > 1 {
                                Label("本周有多项安排重叠，请核对调课或分组。", systemImage: "exclamationmark.circle")
                                    .font(KaiFont.caption).foregroundStyle(.orange).padding(16)
                                    .liquidGlass(tint: .orange)
                            }
                        }.padding(.horizontal, 22).padding(.vertical, 8)
                    }
                }.scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: min(CGFloat(alternatives.count) * 270 + 60, 650))
                Spacer(minLength: 0)
            }.frame(maxWidth: 520)
        }.font(KaiFont.body)
            .sheet(item: $editing) { value in
                CourseEditorView(semester: store.semester, course: value) { try store.saveCourse($0) }
                    .font(KaiFont.body).buttonStyle(GlassButtonStyle()).presentationBackground(.ultraThinMaterial)
            }
            .confirmationDialog("删除「\(deleting?.name ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("删除此课程安排", role: .destructive) {
                    if let value = deleting { store.deleteCourse(value.id) }
                    deleting = nil
                    if alternatives.isEmpty { close() }
                }
            }
    }
    private func detailCard(_ value: Course) -> some View {
        let active = value.isActive(week: week)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 7) {
                    if !active {
                        Text("非本周").font(KaiFont.caption2).padding(.horizontal, 8).padding(.vertical, 4)
                            .liquidGlass(tint: .gray, radius: 9)
                    }
                    Text(value.name).font(KaiFont.title2.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Button { editing = value } label: {
                    Text("编辑").font(KaiFont.subheadline).padding(.horizontal, 12).padding(.vertical, 9)
                        .liquidGlass(tint: active ? value.color.tint : .gray, radius: 14, interactive: true)
                }.buttonStyle(.plain).accessibilityLabel("编辑" + value.name)
                GlassIconButton(symbol: "trash", label: "删除" + value.name, tint: .red) { deleting = value }
                    .accessibilityIdentifier("delete-course-" + value.name)
            }
            Text("第 \(value.weekText) 周").font(KaiFont.body)
            Text(AcademicCalendar.weekdayNames[value.weekday - 1] + " | " + value.periodText + " | " + timeText(value))
                .font(KaiFont.subheadline).fixedSize(horizontal: false, vertical: true)
            Text("教室：" + (value.location.isEmpty ? "待补充" : value.location)).font(KaiFont.body)
            Text("老师：" + (value.teacher.isEmpty ? "待补充" : value.teacher)).font(KaiFont.body)
            if !value.note.isEmpty { Text(value.note).font(KaiFont.caption) }
            if !value.sourceText.isEmpty {
                DisclosureGroup("导入原文") { Text(value.sourceText).font(KaiFont.caption).textSelection(.enabled) }
                    .font(KaiFont.caption).padding(.top, 3)
            }
        }.padding(22).frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(active ? value.color.tint : Color.secondary)
            .liquidGlass(tint: active ? value.color.tint : .gray, radius: 26)
            .contextMenu {
                Button("编辑课程", systemImage: "pencil") { editing = value }
                Button("删除课程", systemImage: "trash", role: .destructive) { deleting = value }
            }
    }
    private func timeText(_ value: Course) -> String {
        guard let first = store.semester.periods.first(where: { $0.id == value.startPeriod }),
              let last = store.semester.periods.first(where: { $0.id == value.endPeriod }) else { return "" }
        return first.startText + "–" + last.endText
    }
}
