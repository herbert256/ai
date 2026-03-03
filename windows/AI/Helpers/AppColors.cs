using System.Windows.Media;

namespace AI.Helpers;

public static class AppColors
{
    public static readonly Color Primary = Color.FromRgb(0x4A, 0x9E, 0xFF);
    public static readonly Color Secondary = Color.FromRgb(0x3A, 0x8E, 0xEF);
    public static readonly Color Background = Color.FromRgb(0x0A, 0x0A, 0x0A);
    public static readonly Color Surface = Color.FromRgb(0x0A, 0x0A, 0x0A);
    public static readonly Color SurfaceVariant = Color.FromRgb(0x0F, 0x34, 0x60);
    public static readonly Color OnBackground = Color.FromRgb(0xEE, 0xEE, 0xEE);
    public static readonly Color OnSurface = Color.FromRgb(0xEE, 0xEE, 0xEE);
    public static readonly Color OnSurfaceVariant = Color.FromRgb(0x88, 0x88, 0x88);
    public static readonly Color Error = Color.FromRgb(0xFF, 0x47, 0x57);

    public static readonly Color Success = Color.FromRgb(0x2E, 0xCC, 0x71);
    public static readonly Color Warning = Color.FromRgb(0xF3, 0x9C, 0x12);
    public static readonly Color Info = Color.FromRgb(0x34, 0x98, 0xDB);

    public static readonly Color StateOk = Success;
    public static readonly Color StateError = Error;
    public static readonly Color StateNotUsed = OnSurfaceVariant;
    public static readonly Color StateInactive = Color.FromRgb(0x55, 0x55, 0x55);

    public static readonly Color CardBackground = Color.FromRgb(0x12, 0x12, 0x15);
    public static readonly Color CardBorder = Color.FromRgb(0x22, 0x22, 0x28);
    public static readonly Color FieldBackground = Color.FromRgb(0x15, 0x15, 0x1A);
    public static readonly Color FieldBorder = Color.FromRgb(0x2A, 0x2A, 0x35);

    public static readonly Color TextPrimary = OnBackground;
    public static readonly Color TextSecondary = OnSurfaceVariant;
    public static readonly Color TextTertiary = Color.FromRgb(0x66, 0x66, 0x66);
    public static readonly Color TextHint = Color.FromRgb(0x55, 0x55, 0x55);

    // Brush helpers
    public static SolidColorBrush ToBrush(this Color c) => new(c);

    public static SolidColorBrush ColorForState(string state) => state switch
    {
        "ok" => new SolidColorBrush(StateOk),
        "error" => new SolidColorBrush(StateError),
        "inactive" => new SolidColorBrush(StateInactive),
        _ => new SolidColorBrush(StateNotUsed)
    };
}
