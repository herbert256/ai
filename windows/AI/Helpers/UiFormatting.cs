namespace AI.Helpers;

public static class UiFormatting
{
    public static string FormatCompactNumber(long value) => value switch
    {
        >= 1_000_000_000 => $"{value / 1_000_000_000.0:F1}B",
        >= 1_000_000 => $"{value / 1_000_000.0:F1}M",
        >= 1_000 => $"{value / 1_000.0:F1}K",
        _ => value.ToString()
    };

    public static string FormatTokenPricePerMillion(double pricePerToken)
    {
        var perMillion = pricePerToken * 1_000_000;
        return perMillion switch
        {
            >= 1 => $"${perMillion:F2} / 1M tokens",
            >= 0.01 => $"${perMillion:F4} / 1M tokens",
            _ => $"${perMillion:F6} / 1M tokens"
        };
    }

    public static string FormatUsd(double value, int decimals = 8) =>
        $"${value.ToString($"F{decimals}")}";

    public static string FormatCents(double value, int decimals = 4) =>
        $"{(value * 100).ToString($"F{decimals}")}c";

    public static string FormatDecimal(double value, int decimals = 2) =>
        value.ToString($"F{decimals}");

    public static string FormatDate(DateTimeOffset date) =>
        date.LocalDateTime.ToString("MMM d, yyyy h:mm tt");

    public static string FormatRelativeDate(DateTimeOffset date)
    {
        var span = DateTimeOffset.Now - date;
        return span.TotalMinutes switch
        {
            < 1 => "just now",
            < 60 => $"{(int)span.TotalMinutes}m ago",
            < 1440 => $"{(int)span.TotalHours}h ago",
            < 10080 => $"{(int)span.TotalDays}d ago",
            _ => date.LocalDateTime.ToString("MMM d, yyyy")
        };
    }

    public static string FormatDuration(int ms) => ms switch
    {
        < 1000 => $"{ms}ms",
        < 60000 => $"{ms / 1000.0:F1}s",
        _ => $"{ms / 60000}m {(ms % 60000) / 1000}s"
    };

    public static string FormatBytes(long bytes) => bytes switch
    {
        >= 1_073_741_824 => $"{bytes / 1_073_741_824.0:F1} GB",
        >= 1_048_576 => $"{bytes / 1_048_576.0:F1} MB",
        >= 1_024 => $"{bytes / 1_024.0:F1} KB",
        _ => $"{bytes} B"
    };
}
