using System.Text;
using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.Helpers;

namespace AI.Views.Admin;

public partial class TraceView : UserControl
{
    private List<TraceFileInfo> _allTraces = new();
    private ApiTrace? _currentTrace;
    private string _activeTab = "all";

    public TraceView()
    {
        InitializeComponent();
        Loaded += (_, _) => LoadTraces();
    }

    private void LoadTraces()
    {
        _allTraces = ApiTracer.Instance.GetTraceFiles();
        ApplyFilter();
    }

    private void ApplyFilter()
    {
        var query = SearchBox.Text.Trim().ToLowerInvariant();
        var filtered = string.IsNullOrEmpty(query)
            ? _allTraces
            : _allTraces.Where(t =>
                t.Hostname.ToLowerInvariant().Contains(query) ||
                (t.Model?.ToLowerInvariant().Contains(query) ?? false) ||
                t.StatusCode.ToString().Contains(query)
            ).ToList();

        TraceList.ItemsSource = filtered;
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void TraceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TraceList.SelectedItem is not TraceFileInfo info) return;
        _currentTrace = ApiTracer.Instance.ReadTrace(info.Filename);
        ShowTab(_activeTab);
    }

    private void ShowTab(string tab)
    {
        _activeTab = tab;
        if (_currentTrace == null)
        {
            DetailBox.Text = "Select a trace to view details.";
            return;
        }

        var sb = new StringBuilder();
        var req = _currentTrace.Request;
        var res = _currentTrace.Response;

        switch (tab)
        {
            case "all":
                sb.AppendLine($"URL: {req.Url}");
                sb.AppendLine($"Method: {req.Method}");
                sb.AppendLine($"Status: {res.StatusCode}");
                sb.AppendLine($"Hostname: {_currentTrace.Hostname}");
                sb.AppendLine($"Model: {_currentTrace.Model ?? "N/A"}");
                sb.AppendLine($"Timestamp: {_currentTrace.TimestampDate:yyyy-MM-dd HH:mm:ss.fff}");
                sb.AppendLine();
                sb.AppendLine("--- Request Headers ---");
                foreach (var (key, value) in req.Headers)
                    sb.AppendLine($"{key}: {MaskAuthHeader(key, value)}");
                sb.AppendLine();
                sb.AppendLine("--- Response Headers ---");
                foreach (var (key, value) in res.Headers)
                    sb.AppendLine($"{key}: {value}");
                sb.AppendLine();
                sb.AppendLine("--- Request Body ---");
                sb.AppendLine(FormatJsonOrText(req.Body));
                sb.AppendLine();
                sb.AppendLine("--- Response Body ---");
                sb.AppendLine(FormatJsonOrText(res.Body));
                break;

            case "req-headers":
                foreach (var (key, value) in req.Headers)
                    sb.AppendLine($"{key}: {MaskAuthHeader(key, value)}");
                break;

            case "res-headers":
                foreach (var (key, value) in res.Headers)
                    sb.AppendLine($"{key}: {value}");
                break;

            case "req-body":
                sb.Append(FormatJsonOrText(req.Body) ?? "(empty)");
                break;

            case "res-body":
                sb.Append(FormatJsonOrText(res.Body) ?? "(empty)");
                break;
        }

        DetailBox.Text = sb.ToString();
    }

    private static string MaskAuthHeader(string key, string value)
    {
        if (key.Equals("Authorization", StringComparison.OrdinalIgnoreCase) ||
            key.Equals("x-api-key", StringComparison.OrdinalIgnoreCase))
        {
            return value.Length > 12 ? $"{value[..8]}...{value[^4..]}" : "***";
        }
        return value;
    }

    private static string? FormatJsonOrText(string? text)
    {
        if (string.IsNullOrEmpty(text)) return null;
        try
        {
            var doc = System.Text.Json.JsonDocument.Parse(text);
            return System.Text.Json.JsonSerializer.Serialize(doc, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
        }
        catch
        {
            return text;
        }
    }

    // Tab click handlers

    private void TabAll_Click(object sender, RoutedEventArgs e) => ShowTab("all");
    private void TabReqHeaders_Click(object sender, RoutedEventArgs e) => ShowTab("req-headers");
    private void TabResHeaders_Click(object sender, RoutedEventArgs e) => ShowTab("res-headers");
    private void TabReqBody_Click(object sender, RoutedEventArgs e) => ShowTab("req-body");
    private void TabResBody_Click(object sender, RoutedEventArgs e) => ShowTab("res-body");

    // Action buttons

    private void RefreshBtn_Click(object sender, RoutedEventArgs e) => LoadTraces();

    private void CopyBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(DetailBox.Text))
            Clipboard.SetText(DetailBox.Text);
    }

    private void ClearAllBtn_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show("Clear all API traces? This cannot be undone.",
            "Clear Traces", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (result != MessageBoxResult.Yes) return;

        ApiTracer.Instance.ClearTraces();
        _currentTrace = null;
        DetailBox.Text = "Traces cleared.";
        LoadTraces();
    }
}
