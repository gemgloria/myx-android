import SwiftUI

struct WeeklyScheduleView: View {
    @EnvironmentObject private var store: ScheduleStore
    @Environment(\.dynamicTypeSize) private var typeSize
    @Binding var chosenWeek: Int?
    @State private var showWeekPicker = false
    @State private var showImport = false
    @State private var showAdd = false
    @State private var now = AppPreview.date
    let openCourse: (Course, Int) -> Void
    private var week: Int { min(max(chosenWeek ?? AcademicCalendar.nearestWeek(on: now, semester: store.semester), 1), store.semester.weekCount) }
    private var dayCount: Int { store.state.preferences.showWeekends ? 7 : 5 }
    private let rowHeight: CGFloat = 86

    var body: some View {
        ZStack {
            AppBackground()
            VStack(spacing: 16) {
                header
                weekNavigation
                if store.semester.courses.isEmpty {
                    Spacer()
                    EmptyScheduleView()
                    Button { showImport = true } label: {
                        Label("导入课表", systemImage: "square.and.arrow.down")
                            .font(KaiFont.headline).padding(.horizontal, 26).padding(.vertical, 15)
                            .liquidGlass(tint: .indigo, interactive: true)
                    }.buttonStyle(.plain)
                    Button("体验截图中的示例课表") { store.loadExample() }
                        .font(KaiFont.subheadline).padding(12)
                    Spacer()
                } else if typeSize.isAccessibilitySize {
                    accessibleList
                } else {
                    GeometryReader { proxy in
                        let cellWidth = max(38, (proxy.size.width - 42 - CGFloat(dayCount - 1) * 4) / CGFloat(dayCount))
                        ScrollView(.horizontal, showsIndicators: false) {
                            VStack(spacing: 10) {
                                weekdayHeader(cellWidth: cellWidth)
                                ScrollView(.vertical, showsIndicators: false) {
                                    timetable(cellWidth: cellWidth).padding(.bottom, 20)
                                }
                            }.frame(width: 42 + CGFloat(dayCount) * cellWidth + CGFloat(dayCount - 1) * 4)
                        }
                    }
                }
            }.padding(.horizontal, 16).padding(.top, 10)
        }.toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showWeekPicker) {
                WeekPickerView(semester: store.semester, selected: week) { chosenWeek = $0 }
            }
            .sheet(isPresented: $showImport) { ImportView() }
            .sheet(isPresented: $showAdd) { CourseEditorView(semester: store.semester) { try store.saveCourse($0) } }
            .onChange(of: store.semester.id) { _, _ in chosenWeek = nil }
            .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { now = $0 }
    }

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text("烨昕的课表").font(KaiFont.system(size: 26, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                Menu {
                    ForEach(store.state.semesters) { semester in
                        Button { store.selectSemester(semester.id) } label: {
                            if semester.id == store.semester.id { Label(semester.name, systemImage: "checkmark") }
                            else { Text(semester.name) }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(store.semester.name).lineLimit(1)
                        Image(systemName: "chevron.down").font(KaiFont.system(size: 8, weight: .semibold))
                    }.font(KaiFont.caption).foregroundStyle(.secondary).padding(.horizontal, 9).padding(.vertical, 7)
                        .liquidGlass(radius: 14, interactive: true)
                }.accessibilityLabel("选择学期")
            }
            Spacer(minLength: 0)
            GlassGroup {
                HStack(spacing: 8) {
                    GlassIconButton(symbol: "square.and.arrow.down", label: "导入课表") { showImport = true }
                    GlassIconButton(symbol: "plus", label: "添加课程", tint: .indigo) { showAdd = true }
                }
            }
        }
    }

    private var weekNavigation: some View {
        HStack(spacing: 10) {
            Button { chosenWeek = max(1, week - 1) } label: {
                Image(systemName: "chevron.left").frame(width: 40, height: 44)
            }.disabled(week == 1).accessibilityLabel("上一周")
            Button { showWeekPicker = true } label: {
                HStack(spacing: 6) {
                    Text("第 \(week) 周").font(KaiFont.headline)
                    Image(systemName: "chevron.down").font(KaiFont.caption2)
                }.frame(minHeight: 44)
            }.buttonStyle(.plain)
            Text(weekDateText).font(KaiFont.caption).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            if chosenWeek != nil {
                Button("本周") { chosenWeek = nil }.font(KaiFont.caption.weight(.medium)).frame(minHeight: 44)
            }
            Button { chosenWeek = min(store.semester.weekCount, week + 1) } label: {
                Image(systemName: "chevron.right").frame(width: 32, height: 44)
            }.disabled(week == store.semester.weekCount).accessibilityLabel("下一周")
        }.padding(.horizontal, 6).liquidGlass(radius: 24)
    }
    private var weekDateText: String {
        let monday = AcademicCalendar.date(week: week, semester: store.semester)
        let sunday = AcademicCalendar.addDays(6, to: monday)
        return monday.formatted(.dateTime.month(.twoDigits).day()) + " – " + sunday.formatted(.dateTime.day())
    }

    private func weekdayHeader(cellWidth: CGFloat) -> some View {
        HStack(spacing: 4) {
            Text("\(AcademicCalendar.calendar.component(.month, from: AcademicCalendar.date(week: week, semester: store.semester)))月")
                .font(KaiFont.caption2).foregroundStyle(.secondary).frame(width: 38)
            ForEach(1...dayCount, id: \.self) { day in
                let date = AcademicCalendar.date(week: week, weekday: day, semester: store.semester)
                let isToday = AcademicCalendar.calendar.isDate(date, inSameDayAs: now)
                VStack(spacing: 5) {
                    Text(String(AcademicCalendar.weekdayNames[day - 1].suffix(1))).font(KaiFont.caption)
                    Text("\(AcademicCalendar.calendar.component(.day, from: date))").font(KaiFont.subheadline.weight(.semibold))
                }.foregroundStyle(isToday ? Color.indigo : .primary)
                    .frame(width: cellWidth, height: 53)
                    .background {
                        if isToday { RoundedRectangle(cornerRadius: 17).fill(.indigo.opacity(0.1)) }
                    }
            }
        }
    }
    private func timetable(cellWidth: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            VStack(spacing: 0) {
                ForEach(store.semester.periods) { period in
                    HStack(alignment: .top, spacing: 4) {
                        VStack(spacing: 4) {
                            Text("\(period.id)").font(KaiFont.caption.weight(.medium)).foregroundStyle(.primary)
                            Text(period.startText).font(KaiFont.system(size: 9, design: .rounded))
                            Text(period.endText).font(KaiFont.system(size: 9, design: .rounded))
                        }.foregroundStyle(.secondary).frame(width: 38).padding(.top, 10)
                        Rectangle().fill(.primary.opacity(0.05)).frame(height: 0.5).padding(.top, rowHeight - 1)
                    }.frame(height: rowHeight)
                }
            }
            GlassGroup {
                HStack(alignment: .top, spacing: 4) {
                    ForEach(1...dayCount, id: \.self) { day in
                        ZStack(alignment: .topLeading) {
                            Color.clear
                            ForEach(CourseLayout.placements(courses: store.semester.courses, weekday: day, week: week)) { placement in
                                courseCard(placement, width: cellWidth)
                                    .offset(y: CGFloat(placement.startPeriod - 1) * rowHeight + 3)
                            }
                        }.frame(width: cellWidth, height: CGFloat(store.semester.periods.count) * rowHeight)
                    }
                }.padding(.leading, 42)
            }
        }.frame(height: CGFloat(store.semester.periods.count) * rowHeight)
    }
    private func courseCard(_ placement: CoursePlacement, width: CGFloat) -> some View {
        let course = placement.course
        let height = CGFloat(placement.endPeriod - placement.startPeriod + 1) * rowHeight - 7
        return Button { openCourse(course, week) } label: {
            VStack(alignment: .leading, spacing: 5) {
                if placement.alternativesCount > 1 {
                    Text("\(placement.alternativesCount)").font(KaiFont.system(size: 9))
                        .padding(.horizontal, 5).padding(.vertical, 2).liquidGlass(tint: course.color.tint, radius: 8)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                Text(course.name).font(KaiFont.system(size: 11, weight: .semibold)).lineLimit(max(2, Int(height / 17) - 2))
                Spacer(minLength: 2)
                if !course.location.isEmpty { Text(course.location).font(KaiFont.system(size: 10)).lineLimit(3).opacity(0.85) }
                if placement.activeCount > 1 {
                    Image(systemName: "exclamationmark.circle").font(KaiFont.system(size: 10))
                }
            }.foregroundStyle(course.color.tint)
                .padding(.horizontal, width < 30 ? 3 : 7).padding(.vertical, 9)
                .frame(width: width, height: height, alignment: .topLeading)
                .liquidGlass(tint: course.color.tint, radius: 14, interactive: true)
        }.buttonStyle(.plain).accessibilityLabel("\(course.name)，\(course.location)，\(course.periodText)，本周有课。同一时段共有\(placement.alternativesCount)项安排，点开查看。")
    }
    private var accessibleList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ForEach(1...dayCount, id: \.self) { day in
                    let placements = CourseLayout.placements(courses: store.semester.courses, weekday: day, week: week)
                    if !placements.isEmpty {
                        Text(AcademicCalendar.weekdayNames[day - 1]).font(KaiFont.headline)
                        ForEach(placements) { placement in
                            let course = placement.course
                            Button { openCourse(course, week) } label: {
                                VStack(alignment: .leading, spacing: 7) {
                                    Text(course.name).font(KaiFont.headline)
                                    Text(course.periodText + " · " + course.location).font(KaiFont.subheadline).foregroundStyle(.secondary)
                                }.padding(18).frame(maxWidth: .infinity, alignment: .leading).liquidGlass(tint: course.color.tint)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(.bottom, 20)
        }
    }
}

struct WeekPickerView: View {
    let semester: Semester
    let selected: Int
    let choose: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
                        ForEach(1...semester.weekCount, id: \.self) { week in
                            Button { choose(week); dismiss() } label: {
                                VStack(spacing: 7) {
                                    Text("第\(week)周").font(KaiFont.headline)
                                    Text(AcademicCalendar.date(week: week, semester: semester).formatted(.dateTime.month().day()))
                                        .font(KaiFont.caption).foregroundStyle(.secondary)
                                }.frame(maxWidth: .infinity).padding(.vertical, 17)
                                    .liquidGlass(tint: week == selected ? .indigo : .clear, radius: 20, interactive: true)
                            }.buttonStyle(.plain)
                        }
                    }.padding(20)
                }
            }.navigationTitle("选择周次").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } } }
        }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}
