// Biquad equalizer (RBJ Audio EQ Cookbook filters), processed in double precision.
// Header-only, no platform dependencies, so it can be unit-tested.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace uac {

struct EqBand {
    enum class Type : int { Peak = 0, LowShelf = 1, HighShelf = 2 };
    Type type = Type::Peak;
    double frequency = 1000;  // Hz
    double gainDb = 0;
    double q = 1.0;
};

class Equalizer {
public:
    // Replaces the filters. Bands with no gain, or above the Nyquist limit, are skipped.
    void configure(const std::vector<EqBand>& bands, double preampDb, double sampleRate, size_t channels) {
        const size_t oldCount = sections_.size();
        sections_.clear();
        for (const EqBand& b : bands) {
            if (std::fabs(b.gainDb) < 0.01 || b.frequency <= 0 || b.frequency >= sampleRate * 0.49) continue;
            sections_.push_back(design(b, sampleRate));
        }
        preamp_ = std::pow(10.0, preampDb / 20.0);
        // Keep the filter memory while a slider is being dragged (same layout), so changes don't click.
        if (sections_.size() != oldCount || channels != channels_ || sampleRate != sampleRate_) {
            state_.assign(sections_.size() * channels * 2, 0.0);
        }
        channels_ = channels;
        sampleRate_ = sampleRate;
    }

    void reset() { std::fill(state_.begin(), state_.end(), 0.0); }

    bool active() const { return !sections_.empty() || std::fabs(preamp_ - 1.0) > 1e-9; }

    // In-place on interleaved float samples.
    void process(float* samples, size_t frames) {
        const size_t sectionCount = sections_.size();
        for (size_t f = 0; f < frames; ++f) {
            for (size_t c = 0; c < channels_; ++c) {
                double x = double(samples[f * channels_ + c]) * preamp_;
                for (size_t s = 0; s < sectionCount; ++s) {
                    const Section& k = sections_[s];
                    double* z = &state_[(s * channels_ + c) * 2];
                    const double y = k.b0 * x + z[0];  // transposed direct form II
                    z[0] = k.b1 * x - k.a1 * y + z[1];
                    z[1] = k.b2 * x - k.a2 * y;
                    x = y;
                }
                samples[f * channels_ + c] = float(x);
            }
        }
    }

    // Combined gain in dB at `frequency`, for tests (the app draws its own curve).
    double responseDb(double frequency, double sampleRate) const {
        const double w = 2 * M_PI * frequency / sampleRate;
        double magnitude = preamp_;
        for (const Section& k : sections_) {
            // |H(e^jw)| from the normalised coefficients
            const double cw = std::cos(w), c2w = std::cos(2 * w), sw = std::sin(w), s2w = std::sin(2 * w);
            const double nr = k.b0 + k.b1 * cw + k.b2 * c2w, ni = -(k.b1 * sw + k.b2 * s2w);
            const double dr = 1 + k.a1 * cw + k.a2 * c2w, di = -(k.a1 * sw + k.a2 * s2w);
            magnitude *= std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
        }
        return 20 * std::log10(magnitude);
    }

private:
    struct Section {
        double b0, b1, b2, a1, a2;
    };

    static Section design(const EqBand& b, double fs) {
        const double a = std::pow(10.0, b.gainDb / 40.0);
        const double w0 = 2 * M_PI * b.frequency / fs;
        const double cw = std::cos(w0);
        const double alpha = std::sin(w0) / (2 * std::max(b.q, 0.05));
        const double sa = 2 * std::sqrt(a) * alpha;
        double b0, b1, b2, a0, a1, a2;
        switch (b.type) {
            case EqBand::Type::LowShelf:
                b0 = a * ((a + 1) - (a - 1) * cw + sa);
                b1 = 2 * a * ((a - 1) - (a + 1) * cw);
                b2 = a * ((a + 1) - (a - 1) * cw - sa);
                a0 = (a + 1) + (a - 1) * cw + sa;
                a1 = -2 * ((a - 1) + (a + 1) * cw);
                a2 = (a + 1) + (a - 1) * cw - sa;
                break;
            case EqBand::Type::HighShelf:
                b0 = a * ((a + 1) + (a - 1) * cw + sa);
                b1 = -2 * a * ((a - 1) + (a + 1) * cw);
                b2 = a * ((a + 1) + (a - 1) * cw - sa);
                a0 = (a + 1) - (a - 1) * cw + sa;
                a1 = 2 * ((a - 1) - (a + 1) * cw);
                a2 = (a + 1) - (a - 1) * cw - sa;
                break;
            case EqBand::Type::Peak:
            default:
                b0 = 1 + alpha * a;
                b1 = -2 * cw;
                b2 = 1 - alpha * a;
                a0 = 1 + alpha / a;
                a1 = -2 * cw;
                a2 = 1 - alpha / a;
                break;
        }
        return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    std::vector<Section> sections_;
    std::vector<double> state_;  // two delay values per section per channel
    double preamp_ = 1.0;
    size_t channels_ = 2;
    double sampleRate_ = 0;
};

}  // namespace uac
