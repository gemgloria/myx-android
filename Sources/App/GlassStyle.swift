import SwiftUI

extension CourseColor {
    var tint: Color {
        switch self {
        case .violet: return Color(red: 0.48, green: 0.32, blue: 0.84)
        case .teal: return Color(red: 0.04, green: 0.51, blue: 0.49)
        case .blue: return Color(red: 0.12, green: 0.44, blue: 0.81)
        case .amber: return Color(red: 0.68, green: 0.39, blue: 0.05)
        case .rose: return Color(red: 0.73, green: 0.27, blue: 0.44)
        case .indigo: return Color(red: 0.36, green: 0.38, blue: 0.76)
        }
    }
}

struct GlassSurface: ViewModifier {
    var tint: Color = .clear
    var radius: CGFloat = 22
    var interactive = false
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if reduceTransparency {
            content.background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(tint.opacity(0.2), lineWidth: 1))
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular.tint(tint.opacity(0.1)).interactive(interactive), in: shape)
        } else {
            content.background(.ultraThinMaterial, in: shape)
                .background(tint.opacity(0.07), in: shape)
                .overlay(shape.strokeBorder(.primary.opacity(0.07), lineWidth: 0.75))
                .shadow(color: .black.opacity(0.035), radius: 10, y: 4)
        }
    }
}

struct GlassButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding(.horizontal, 12).padding(.vertical, 9)
            .liquidGlass(tint: .indigo, radius: 18, interactive: true)
            .opacity(isEnabled ? (configuration.isPressed ? 0.65 : 1) : 0.4)
    }
}

extension View {
    func liquidGlass(tint: Color = .clear, radius: CGFloat = 22, interactive: Bool = false) -> some View {
        modifier(GlassSurface(tint: tint, radius: radius, interactive: interactive))
    }
    func glassField() -> some View { padding(14).liquidGlass(radius: 16) }
}

struct GlassGroup<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        if #available(iOS 26.0, *) { GlassEffectContainer(spacing: 4) { content } }
        else { content }
    }
}

struct AppBackground: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(uiColor: .systemBackground)
                Circle().fill(Color.indigo.opacity(scheme == .dark ? 0.17 : 0.12))
                    .frame(width: proxy.size.width * 1.1).blur(radius: 80).offset(x: -130, y: -230)
                Circle().fill(Color.cyan.opacity(scheme == .dark ? 0.10 : 0.09))
                    .frame(width: proxy.size.width).blur(radius: 90).offset(x: 150, y: 160)
                Circle().fill(Color.pink.opacity(0.06)).frame(width: proxy.size.width)
                    .blur(radius: 100).offset(x: -80, y: 440)
            }
        }.ignoresSafeArea()
    }
}

struct GlassIconButton: View {
    let symbol: String
    let label: String
    var tint: Color = .primary
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(KaiFont.system(size: 18, weight: .medium))
                .foregroundStyle(tint).frame(width: 46, height: 46)
                .liquidGlass(tint: tint, radius: 23, interactive: true)
        }.buttonStyle(.plain).accessibilityLabel(label)
    }
}

struct GlassSection<Content: View>: View {
    var title: String
    let content: Content
    init(title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            Text(title).font(KaiFont.subheadline.weight(.medium)).foregroundStyle(.secondary)
            content
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).liquidGlass()
    }
}

struct EmptyScheduleView: View {
    var title = "把这个学期装进口袋"
    var subtitle = "导入教务课表，或添加第一门课程。"
    var symbol = "calendar.badge.plus"
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol).font(KaiFont.system(size: 34, weight: .light)).foregroundStyle(.indigo)
                .frame(width: 80, height: 80).liquidGlass(tint: .indigo, radius: 28)
            Text(title).font(KaiFont.title3.weight(.semibold))
            Text(subtitle).font(KaiFont.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.padding(28).frame(maxWidth: .infinity)
    }
}
