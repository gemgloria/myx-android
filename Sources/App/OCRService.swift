import UIKit
import Vision

enum OCRService {
    static func recognize(data: Data, manualColumns: Bool) async throws -> ImportResult {
        try await Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            guard let image = UIImage(data: data), let cgImage = normalized(image).cgImage else { throw ScheduleError.unreadable }
            let fullLines = try recognizeLines(cgImage)
            let region: GridRegion
            if manualColumns { region = .init(left: 0, top: 0, right: 1, bottom: 1) }
            else {
                guard let grid = TimetableParser.detectGrid(in: fullLines) else { throw ScheduleError.noGrid }
                region = grid
            }
            var candidates: [ImportCandidate] = []
            var warnings: [String] = []
            let columnWidth = (region.right - region.left) / 7
            for day in 1...7 {
                try Task.checkCancellation()
                let left = region.left + Double(day - 1) * columnWidth
                let rect = CGRect(x: left * Double(cgImage.width), y: region.top * Double(cgImage.height),
                                  width: columnWidth * Double(cgImage.width), height: (region.bottom - region.top) * Double(cgImage.height))
                    .intersection(CGRect(x: 0, y: 0, width: CGFloat(cgImage.width), height: CGFloat(cgImage.height))).integral
                guard let column = cgImage.cropping(to: rect) else { throw ScheduleError.unreadable }
                let parsed = TimetableParser.parseColumn(try recognizeLines(column), weekday: day)
                candidates.append(contentsOf: parsed.candidates)
                warnings.append(contentsOf: parsed.warnings)
            }
            let deduplicated = TimetableParser.deduplicate(candidates)
            guard !deduplicated.candidates.isEmpty else { throw ScheduleError.noCourses }
            return ImportResult(candidates: deduplicated.candidates,
                                suggestedSemesterName: TimetableParser.semesterName(in: fullLines),
                                warnings: warnings, duplicateCount: deduplicated.duplicateCount)
        }.value
    }

    private static func recognizeLines(_ image: CGImage) throws -> [OCRLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        let languages = try request.supportedRecognitionLanguages()
        request.recognitionLanguages = ["zh-Hans", "en-US"].filter { languages.contains($0) }
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.002
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        return (request.results ?? []).compactMap { observation in
            guard let text = observation.topCandidates(1).first else { return nil }
            let rect = observation.boundingBox
            return OCRLine(text: text.string, confidence: text.confidence, x: rect.minX,
                           y: 1 - rect.maxY, width: rect.width, height: rect.height)
        }
    }

    // Render the UIImage orientation into pixels before geometric column cropping.
    static func normalized(_ image: UIImage) -> UIImage {
        let desiredWidth = min(2400, max(image.size.width, 1800))
        let scale = min(desiredWidth / image.size.width, 6500 / image.size.height)
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
