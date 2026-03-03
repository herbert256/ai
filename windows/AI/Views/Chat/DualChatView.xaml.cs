using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Chat;

public partial class DualChatView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private readonly List<ChatMessage> _messages1 = new();
    private readonly List<ChatMessage> _messages2 = new();

    public DualChatView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var names = AppService.Entries.Select(s => s.DisplayName).ToList();
        Provider1Combo.ItemsSource = names;
        Provider2Combo.ItemsSource = names;
        if (AppService.Entries.Count > 0)
        {
            Provider1Combo.SelectedIndex = 0;
            Provider2Combo.SelectedIndex = Math.Min(1, AppService.Entries.Count - 1);
        }
    }

    private void Provider1Combo_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        UpdateProviderCombo(Provider1Combo, Model1Combo, Status1Text);

    private void Provider2Combo_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        UpdateProviderCombo(Provider2Combo, Model2Combo, Status2Text);

    private void UpdateProviderCombo(ComboBox providerCombo, ComboBox modelCombo, TextBlock statusText)
    {
        var idx = providerCombo.SelectedIndex;
        if (idx < 0 || idx >= AppService.Entries.Count) return;
        var svc = AppService.Entries[idx];
        var models = Vm.AiSettings.GetModels(svc);
        modelCombo.ItemsSource = models.Count > 0 ? models : new List<string> { svc.DefaultModel };
        modelCombo.Text = svc.DefaultModel;
        statusText.Text = svc.DisplayName;
    }

    private AppService? GetService(ComboBox combo)
    {
        var idx = combo.SelectedIndex;
        return idx >= 0 && idx < AppService.Entries.Count ? AppService.Entries[idx] : null;
    }

    private async void SendBothBtn_Click(object sender, RoutedEventArgs e)
    {
        var text = SharedInput.Text.Trim();
        if (string.IsNullOrEmpty(text)) return;
        SharedInput.Text = "";

        var svc1 = GetService(Provider1Combo);
        var svc2 = GetService(Provider2Combo);
        var model1 = Model1Combo.Text?.Trim() ?? "";
        var model2 = Model2Combo.Text?.Trim() ?? "";

        _messages1.Add(new ChatMessage("user", text));
        _messages2.Add(new ChatMessage("user", text));
        AddBubble(Messages1Panel, Scroll1, "user", text);
        AddBubble(Messages2Panel, Scroll2, "user", text);

        var t1 = svc1 != null ? SendStream(svc1, model1, _messages1, Messages1Panel, Scroll1) : Task.CompletedTask;
        var t2 = svc2 != null ? SendStream(svc2, model2, _messages2, Messages2Panel, Scroll2) : Task.CompletedTask;
        await Task.WhenAll(t1, t2);
    }

    private async Task SendStream(AppService svc, string model, List<ChatMessage> messages,
        StackPanel panel, ScrollViewer scroll)
    {
        var apiKey = Vm.AiSettings.GetApiKey(svc);
        var wrapperBorder = AddBubble(panel, scroll, "assistant", "");
        var textBlock = (TextBlock)((Border)wrapperBorder.Child).Child;
        var accumulated = "";
        try
        {
            await foreach (var chunk in Vm.SendChatMessageStream(svc, apiKey, model, messages))
            {
                accumulated += chunk;
                Dispatcher.Invoke(() => { textBlock.Text = accumulated; scroll.ScrollToBottom(); });
            }
            messages.Add(new ChatMessage("assistant", accumulated));
        }
        catch (Exception ex)
        {
            Dispatcher.Invoke(() =>
            {
                textBlock.Text = $"Error: {ex.Message}";
                textBlock.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            });
        }
    }

    private Border AddBubble(StackPanel panel, ScrollViewer scroll, string role, string text)
    {
        var isUser = role == "user";
        var inner = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, FontSize = 13 };
        var bubble = new Border
        {
            Background = isUser
                ? (SolidColorBrush)FindResource("SidebarSelectedBrush")
                : (SolidColorBrush)FindResource("FieldBackgroundBrush"),
            BorderBrush = (SolidColorBrush)FindResource("CardBorderBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(10, 6, 10, 6),
            Child = inner
        };
        var wrapper = new Border
        {
            HorizontalAlignment = isUser ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            Margin = new Thickness(0, 3, 0, 3),
            Child = bubble
        };
        Dispatcher.Invoke(() => { panel.Children.Add(wrapper); scroll.ScrollToBottom(); });
        return wrapper;
    }

    private void ClearBothBtn_Click(object sender, RoutedEventArgs e)
    {
        _messages1.Clear(); _messages2.Clear();
        Messages1Panel.Children.Clear(); Messages2Panel.Children.Clear();
    }
}
