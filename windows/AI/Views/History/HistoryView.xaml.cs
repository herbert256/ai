using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.Views.Reports;

namespace AI.Views.History;

public partial class HistoryView : UserControl
{
    public HistoryView()
    {
        InitializeComponent();
        Loaded += (_, _) => LoadReports();
    }

    private void LoadReports()
    {
        var reports = ReportStorage.Instance.LoadAll();
        ReportList.ItemsSource = reports;
        if (reports.Count == 0)
        {
            DetailTitle.Text = "No reports yet";
            DetailPrompt.Text = "Generate a report to see it here.";
        }
    }

    private void ReportList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ReportList.SelectedItem is not StoredReport report) return;
        DetailTitle.Text = report.Title;
        DetailPrompt.Text = report.Prompt;
        ResultsPanel.Children.Clear();

        foreach (var result in report.Results)
        {
            var svc = AppService.FindById(result.ProviderId);
            if (svc == null) continue;

            var response = new AnalysisResponse(svc, result.Analysis, result.Error,
                new TokenUsage(result.InputTokens, result.OutputTokens, result.ApiCost),
                result.AgentName);
            response.Citations = result.Citations;

            var display = new ContentDisplay();
            display.Render(response);
            ResultsPanel.Children.Add(display);
        }
    }

    private void RefreshBtn_Click(object sender, RoutedEventArgs e) => LoadReports();
}
