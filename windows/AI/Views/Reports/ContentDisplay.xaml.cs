using System.Windows;
using System.Windows.Controls;
using AI.Data;

namespace AI.Views.Reports;

public partial class ContentDisplay : UserControl
{
    public static readonly DependencyProperty ResponseProperty =
        DependencyProperty.Register(nameof(Response), typeof(AnalysisResponse), typeof(ContentDisplay),
            new PropertyMetadata(null, OnResponseChanged));

    public AnalysisResponse? Response
    {
        get => (AnalysisResponse?)GetValue(ResponseProperty);
        set => SetValue(ResponseProperty, value);
    }

    public ContentDisplay()
    {
        InitializeComponent();
    }

    private static void OnResponseChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is ContentDisplay cd)
            cd.Render(e.NewValue as AnalysisResponse);
    }

    public void Render(AnalysisResponse? response)
    {
        if (response == null) return;

        AgentNameText.Text = response.DisplayName;

        if (response.TokenUsage != null)
        {
            TokenText.Text = $"{response.TokenUsage.InputTokens:N0} in / {response.TokenUsage.OutputTokens:N0} out";
            if (response.TokenUsage.ApiCost.HasValue)
            {
                CostText.Text = $"${response.TokenUsage.ApiCost.Value:F4}";
                CostText.Visibility = Visibility.Visible;
            }
        }

        if (!string.IsNullOrEmpty(response.Error))
        {
            ErrorText.Text = response.Error;
            ErrorText.Visibility = Visibility.Visible;
        }

        AnalysisText.Text = response.Analysis ?? "";

        if (response.Citations?.Count > 0)
        {
            CitationsList.ItemsSource = response.Citations;
            CitationsList.Visibility = Visibility.Visible;
        }
    }
}
