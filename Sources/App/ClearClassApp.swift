import SwiftUI
import WidgetKit

@main
struct ClearClassApp: App {
    @StateObject private var store = ScheduleStore()
    @Environment(\.scenePhase) private var scenePhase
    init() { KaiFont.configureNavigationAppearance() }
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).preferredColorScheme(store.appearance)
                .font(KaiFont.body).buttonStyle(GlassButtonStyle())
                .task { WidgetCenter.shared.reloadAllTimelines() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { WidgetCenter.shared.reloadAllTimelines() }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: ScheduleStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tab = AppPreview.tab
    @State private var viewedWeek: Int? = AppPreview.enabled ? 6 : nil
    @State private var selectedCourse: CourseSelection?
    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                NavigationStack {
                    Group {
                        switch tab {
                        case 1: TodayView { showCourse($0, week: $1) }
                        case 2: SettingsView()
                        default: WeeklyScheduleView(chosenWeek: $viewedWeek) { showCourse($0, week: $1) }
                        }
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle()).clipped()
                // Outside the page and its scroll view: page controls cannot
                // extend their hit regions into the bottom navigation bar.
                tabBar.zIndex(2)
            }.allowsHitTesting(selectedCourse == nil).accessibilityHidden(selectedCourse != nil)
            if let selection = selectedCourse {
                CourseDetailView(course: selection.course, week: selection.week) { selectedCourse = nil }
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.92).combined(with: .opacity))
                    .zIndex(1)
            }
        }.tint(.indigo)
            .animation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.9), value: selectedCourse?.id)
            .onChange(of: store.semester.id) { _, _ in viewedWeek = nil }
            .onAppear {
                if AppPreview.showDetail,
                   let course = store.semester.courses.first(where: { $0.name == "太阳能利用概论" && $0.weekday == 2 }) {
                    showCourse(course, week: 6)
                }
            }
            .alert("提示", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
                Button("知道了", role: .cancel) { store.message = nil }
            } message: { Text(store.message ?? "") }
            .onOpenURL { url in
                guard url.scheme == "clearclass" else { return }
                tab = url.host == "today" ? 1 : 0
                if let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                   let idString = components.queryItems?.first(where: { $0.name == "course" })?.value,
                   let id = UUID(uuidString: idString), let course = store.semester.courses.first(where: { $0.id == id }) {
                    let linkedWeek = components.queryItems?.first(where: { $0.name == "week" })?.value.flatMap(Int.init)
                    let week = linkedWeek.flatMap { (1...store.semester.weekCount).contains($0) ? $0 : nil }
                        ?? AcademicCalendar.nearestWeek(on: .now, semester: store.semester)
                    showCourse(course, week: week)
                }
            }
    }
    private func showCourse(_ course: Course, week: Int) { selectedCourse = .init(course: course, week: week) }
    private var tabBar: some View {
        ZStack {
            Color.clear.contentShape(Rectangle()).onTapGesture {}
            HStack(spacing: 10) {
                tabButton(0, title: "课表", symbol: "calendar")
                tabButton(1, title: "今天", symbol: "sun.max")
                tabButton(2, title: "设置", symbol: "slider.horizontal.3")
            }.padding(7).liquidGlass(radius: 32)
        }.padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 7)
            .fixedSize(horizontal: false, vertical: true)
            .background { AppBackground() }
            .accessibilityIdentifier("bottom-navigation")
    }
    private func tabButton(_ value: Int, title: String, symbol: String) -> some View {
        Button { tab = value } label: {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(KaiFont.system(size: 19))
                Text(title).font(KaiFont.caption)
            }.frame(maxWidth: .infinity).padding(.vertical, 9)
                .foregroundStyle(tab == value ? Color.indigo : .secondary)
                .liquidGlass(tint: tab == value ? .indigo : .clear, radius: 25, interactive: true)
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(title)
            .accessibilityIdentifier("tab-\(value)")
            .accessibilityAddTraits(tab == value ? .isSelected : [])
    }
}

struct CourseSelection: Identifiable {
    let course: Course
    let week: Int
    var id: String { course.id.uuidString + "-" + String(week) }
}
