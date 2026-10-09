import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct ImportView: View {
    @EnvironmentObject private var store: ScheduleStore
    @Environment(\.dismiss) private var dismiss
    @State private var photo: PhotosPickerItem?
    @State private var imageData: Data?
    @State private var image: UIImage?
    @State private var manualColumns = false
    @State private var showCrop = false
    @State private var showFilePicker = false
    @State private var busy = false
    @State private var result: ImportResult?
    @State private var errorText: String?
    @State private var editing: ImportCandidate?
    @State private var selectedSemesterID: UUID?
    @State private var createSemester = false
    @State private var newName = ""
    @State private var firstWeekDate = Date.now
    @State private var replace = false
    @State private var confirmReplace = false
    @State private var work: Task<Void, Never>?

    private var targetSemester: Semester {
        if createSemester {
            return Semester(name: newName, firstMonday: AcademicCalendar.string(from: AcademicCalendar.monday(of: firstWeekDate)),
                            weekCount: max(20, result?.candidates.flatMap { $0.course.weeks }.max() ?? 20))
        }
        return store.state.semesters.first { $0.id == selectedSemesterID } ?? store.semester
    }
    private var selectedCourses: [Course] { result?.candidates.filter(\.selected).map(\.course) ?? [] }
    private var fileTypes: [UTType] {
        [UTType(filenameExtension: "xls"), UTType(filenameExtension: "xlsx"), UTType.commaSeparatedText,
         UTType(filenameExtension: "tsv")].compactMap { $0 }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        if let value = result { review(value) }
                        else { sourcePicker }
                        if busy {
                            HStack(spacing: 12) { ProgressView(); Text("正在本机读取课表…").font(KaiFont.subheadline) }
                                .padding(20).frame(maxWidth: .infinity).liquidGlass()
                        }
                    }.padding(20)
                }.scrollDismissesKeyboard(.interactively)
            }.navigationTitle(result == nil ? "导入课表" : "核对课程").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { work?.cancel(); dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        if result != nil {
                            Button("导入\(selectedCourses.count)门") {
                                if replace && !createSemester && !targetSemester.courses.isEmpty { confirmReplace = true }
                                else { persist() }
                            }.disabled(selectedCourses.isEmpty || busy)
                        }
                    }
                }
                .onAppear { selectedSemesterID = store.semester.id }
                .onChange(of: photo) { _, item in
                    guard let item else { return }
                    work?.cancel()
                    work = Task {
                        do {
                            guard let data = try await item.loadTransferable(type: Data.self), let uiImage = UIImage(data: data) else { throw ScheduleError.unreadable }
                            guard !Task.isCancelled else { return }
                            imageData = data; image = uiImage; result = nil
                        } catch { if !Task.isCancelled { errorText = error.localizedDescription } }
                    }
                }
                .fileImporter(isPresented: $showFilePicker, allowedContentTypes: fileTypes) { value in
                    do {
                        let url = try value.get(); let access = url.startAccessingSecurityScopedResource()
                        defer { if access { url.stopAccessingSecurityScopedResource() } }
                        let data = try Data(contentsOf: url)
                        readSpreadsheet(data)
                    } catch { errorText = error.localizedDescription }
                }
                .sheet(isPresented: $showCrop) {
                    if let image { ImageCropView(image: image) { data in
                        imageData = data; self.image = UIImage(data: data)
                    } }
                }
                .sheet(item: $editing) { candidate in
                    CourseEditorView(semester: targetSemester, course: candidate.course) { course in
                        guard let index = result?.candidates.firstIndex(where: { $0.id == candidate.id }) else { return }
                        result?.candidates[index].course = course
                        result?.candidates[index].warnings = []
                    }
                }
                .alert("无法导入", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                    Button("知道了", role: .cancel) {}
                } message: { Text(errorText ?? "") }
                .confirmationDialog("替换“\(targetSemester.name)”的全部 \(targetSemester.courses.count) 门课程？", isPresented: $confirmReplace, titleVisibility: .visible) {
                    Button("替换并导入", role: .destructive) { persist() }
                }
                .onDisappear { work?.cancel() }
        }
    }

    private var sourcePicker: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("把教务课表带进来").font(KaiFont.title2.weight(.semibold))
            Text("选择理论课表截图或教务处导出的表格。读取后可以逐门核对和修改。")
                .font(KaiFont.subheadline).foregroundStyle(.secondary)
            GlassGroup {
                VStack(spacing: 13) {
                    PhotosPicker(selection: $photo, matching: .images) {
                        importChoice("课表截图", subtitle: "本机文字识别 · 支持多课与分段周次", symbol: "photo.badge.plus")
                    }.buttonStyle(.plain).disabled(busy)
                    Button { showFilePicker = true } label: {
                        importChoice("课表表格", subtitle: "XLS、XLSX、CSV、TSV", symbol: "tablecells")
                    }.buttonStyle(.plain).disabled(busy)
                }
            }
            if let image {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 270)
                    .clipShape(RoundedRectangle(cornerRadius: 18)).frame(maxWidth: .infinity)
                Button { showCrop = true } label: { Label("框选课表区域", systemImage: "crop").padding(14).liquidGlass(interactive: true) }
                    .buttonStyle(.plain).disabled(busy)
                Toggle("没有星期表头", isOn: $manualColumns).tint(.indigo).padding(16).liquidGlass()
                Text(manualColumns ? "请先框选周一到周日七列的课程区域，去掉左侧节次列。七列必须完整且等宽。" : "自动寻找星期表头。截图需保留周一到周日，文字清晰且没有遮挡。")
                    .font(KaiFont.caption).foregroundStyle(.secondary)
                Button { readImage() } label: {
                    Label("识别截图", systemImage: "text.viewfinder").font(KaiFont.headline)
                        .frame(maxWidth: .infinity).padding(17).liquidGlass(tint: .indigo, interactive: true)
                }.buttonStyle(.plain).disabled(busy)
            }
        }
    }
    private func importChoice(_ title: String, subtitle: String, symbol: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: symbol).font(KaiFont.system(size: 24, weight: .light)).foregroundStyle(.indigo).frame(width: 36)
            VStack(alignment: .leading, spacing: 7) {
                Text(title).font(KaiFont.headline)
                Text(subtitle).font(KaiFont.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(KaiFont.caption).foregroundStyle(.secondary)
        }.padding(21).frame(maxWidth: .infinity, alignment: .leading).liquidGlass(tint: .indigo, radius: 26, interactive: true)
    }

    private func review(_ value: ImportResult) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Text("读到 \(value.candidates.count) 门课程").font(KaiFont.title2.weight(.semibold))
                    Text("点课程修改，核对完成后再导入。")
                        .font(KaiFont.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Button("重选") { result = nil; image = nil; imageData = nil; photo = nil }.font(KaiFont.subheadline)
            }
            GlassSection(title: "保存到学期") {
                Toggle("新建学期", isOn: $createSemester).tint(.indigo)
                if createSemester {
                    TextField("学期名称", text: $newName).glassField()
                    DatePicker("第1周的周一", selection: $firstWeekDate, displayedComponents: .date).glassField()
                    Text("截图和表格通常不包含开学日期，请按学校校历设置。选择日期会自动对齐周一。")
                        .font(KaiFont.caption).foregroundStyle(.secondary)
                } else {
                    Picker("目标学期", selection: Binding(get: { selectedSemesterID ?? store.semester.id }, set: { selectedSemesterID = $0 })) {
                        ForEach(store.state.semesters) { Text($0.name).tag($0.id) }
                    }
                    Picker("导入方式", selection: $replace) { Text("追加").tag(false); Text("替换").tag(true) }.pickerStyle(.segmented)
                    if let name = value.suggestedSemesterName { Text("原文件学期：\(name)").font(KaiFont.caption).foregroundStyle(.secondary) }
                }
            }
            if value.duplicateCount > 0 {
                Text("已去除 \(value.duplicateCount) 条跨行重复课程。").font(KaiFont.caption).foregroundStyle(.secondary)
            }
            if !value.warnings.isEmpty {
                GlassSection(title: "请核对") {
                    ForEach(Array(value.warnings.enumerated()), id: \.offset) { Text($0.element).font(KaiFont.caption).foregroundStyle(.secondary) }
                }
            }
            ForEach(value.candidates) { candidate in
                HStack(alignment: .top, spacing: 12) {
                    Button {
                        if let index = result?.candidates.firstIndex(where: { $0.id == candidate.id }) {
                            result?.candidates[index].selected.toggle()
                        }
                    } label: {
                        Image(systemName: candidate.selected ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(.indigo).font(KaiFont.title3).frame(width: 30, height: 44)
                    }.buttonStyle(.plain).accessibilityLabel(candidate.selected ? "取消选择\(candidate.course.name)" : "选择\(candidate.course.name)")
                    Button { editing = candidate } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            Text(candidate.course.name).font(KaiFont.headline)
                            Text(AcademicCalendar.weekdayNames[candidate.course.weekday - 1] + " · " + candidate.course.periodText + " · " + candidate.course.location)
                                .font(KaiFont.caption).foregroundStyle(.secondary)
                            Text("第 \(candidate.course.weekText) 周").font(KaiFont.caption).foregroundStyle(.secondary)
                            if !candidate.warnings.isEmpty {
                                Label(candidate.warnings.joined(separator: "；"), systemImage: "exclamationmark.circle")
                                    .font(KaiFont.caption).foregroundStyle(.orange)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(.plain)
                    Image(systemName: "pencil").font(KaiFont.caption).foregroundStyle(.secondary).padding(.top, 12)
                }.padding(16).liquidGlass(tint: candidate.course.color.tint, radius: 23)
            }
        }
    }
    private func readImage() {
        guard let data = imageData else { return }
        let manual = manualColumns
        busy = true
        work = Task {
            defer { busy = false }
            do {
                let recognized = try await OCRService.recognize(data: data, manualColumns: manual)
                guard !Task.isCancelled else { return }
                accept(recognized)
            } catch { if !Task.isCancelled { errorText = error.localizedDescription } }
        }
    }
    private func readSpreadsheet(_ data: Data) {
        busy = true
        work = Task {
            defer { busy = false }
            do {
                let recognized = try await SpreadsheetService.recognize(data: data)
                guard !Task.isCancelled else { return }
                accept(recognized)
            } catch { if !Task.isCancelled { errorText = error.localizedDescription } }
        }
    }
    private func accept(_ recognized: ImportResult) {
        result = recognized
        newName = recognized.suggestedSemesterName ?? store.semester.name
        createSemester = recognized.suggestedSemesterName.map { !store.semester.name.contains($0) } ?? false
        selectedSemesterID = store.semester.id
        firstWeekDate = AcademicCalendar.date(from: store.semester.firstMonday) ?? .now
    }
    private func persist() {
        do {
            let courses = selectedCourses
            guard !courses.isEmpty else { return }
            let added: Int
            if createSemester {
                var semester = targetSemester
                semester.courses = courses
                try store.saveSemester(semester)
                added = courses.count
            } else { added = try store.importCourses(courses, to: targetSemester.id, replace: replace) }
            store.message = "已导入 \(added) 门课程。"
            dismiss()
        } catch { errorText = error.localizedDescription }
    }
}

struct ImageCropView: View {
    let image: UIImage
    let save: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var left: CGFloat = 0
    @State private var top: CGFloat = 0
    @State private var right: CGFloat = 1
    @State private var bottom: CGFloat = 1
    @State private var errorText: String?
    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                VStack(spacing: 16) {
                    Text("拖动两个角，保留完整的七列课表。")
                        .font(KaiFont.subheadline).foregroundStyle(.secondary).padding(.top, 20)
                    GeometryReader { proxy in
                        let scale = min((proxy.size.width - 32) / image.size.width, (proxy.size.height - 32) / image.size.height)
                        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                        ZStack(alignment: .topLeading) {
                            Image(uiImage: image).resizable().frame(width: size.width, height: size.height)
                            Path { path in
                                path.addRect(CGRect(origin: .zero, size: size))
                                path.addRect(CGRect(x: left * size.width, y: top * size.height,
                                                    width: (right - left) * size.width, height: (bottom - top) * size.height))
                            }.fill(.black.opacity(0.45), style: FillStyle(eoFill: true)).allowsHitTesting(false)
                            Rectangle().stroke(.indigo, lineWidth: 2)
                                .frame(width: (right - left) * size.width, height: (bottom - top) * size.height)
                                .offset(x: left * size.width, y: top * size.height).allowsHitTesting(false)
                            handle(at: CGPoint(x: left * size.width, y: top * size.height))
                                .gesture(DragGesture(coordinateSpace: .named("crop")).onChanged { value in
                                    left = min(max(0, value.location.x / size.width), right - 0.15)
                                    top = min(max(0, value.location.y / size.height), bottom - 0.15)
                                })
                            handle(at: CGPoint(x: right * size.width, y: bottom * size.height))
                                .gesture(DragGesture(coordinateSpace: .named("crop")).onChanged { value in
                                    right = max(min(1, value.location.x / size.width), left + 0.15)
                                    bottom = max(min(1, value.location.y / size.height), top + 0.15)
                                })
                        }.coordinateSpace(name: "crop").frame(width: size.width, height: size.height)
                            .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
                    }
                    Button("重置选区") { left = 0; top = 0; right = 1; bottom = 1 }.padding(15)
                }
            }.navigationTitle("框选课表").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("使用选区") { crop() } }
                }.alert("裁剪失败", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                    Button("知道了", role: .cancel) {}
                } message: { Text(errorText ?? "") }
        }
    }
    private func handle(at point: CGPoint) -> some View {
        Circle().fill(.indigo).frame(width: 22, height: 22)
            .overlay(Circle().stroke(.white, lineWidth: 3)).frame(width: 48, height: 48).contentShape(Rectangle())
            .position(point).accessibilityLabel("裁剪角")
    }
    private func crop() {
        guard let cgImage = OCRService.normalized(image).cgImage else { errorText = "无法读取图片。"; return }
        let rect = CGRect(x: left * CGFloat(cgImage.width), y: top * CGFloat(cgImage.height),
                          width: (right - left) * CGFloat(cgImage.width), height: (bottom - top) * CGFloat(cgImage.height)).integral
            .intersection(CGRect(x: 0, y: 0, width: CGFloat(cgImage.width), height: CGFloat(cgImage.height)))
        guard let cropped = cgImage.cropping(to: rect), let data = UIImage(cgImage: cropped).pngData() else { errorText = "选区无效。"; return }
        save(data); dismiss()
    }
}
