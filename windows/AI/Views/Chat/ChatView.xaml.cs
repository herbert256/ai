using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Chat;

public partial class ChatView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private readonly List<ChatMessage> _messages = new();
    private CancellationTokenSource? _streamCts;
    private bool _isStreaming;

    public ChatView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ProviderCombo.ItemsSource = AppService.Entries.Select(s => s.DisplayName).ToList();
        if (AppService.Entries.Count > 0)
            ProviderCombo.SelectedIndex = 0;
    }

    private void ProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var service = GetSelectedService();
        if (service == null) return;

        var apiKey = Vm.AiSettings.GetApiKey(service);
        ApiKeyHint.Text = string.IsNullOrEmpty(apiKey)
            ? $"No API key configured for {service.DisplayName}"
            : $"Using {service.DisplayName}";

        var models = Vm.AiSettings.GetModels(service);
        ModelCombo.ItemsSource = models.Count > 0 ? models : new List<string> { service.DefaultModel };
        ModelCombo.Text = Vm.AiSettings.GetModel(service) is { } m && !string.IsNullOrEmpty(m)
            ? m : service.DefaultModel;
    }

    private AppService? GetSelectedService()
    {
        var idx = ProviderCombo.SelectedIndex;
        return idx >= 0 && idx < AppService.Entries.Count ? AppService.Entries[idx] : null;
    }

    private string GetModel() => ModelCombo.Text?.Trim() ?? "";

    private async void SendBtn_Click(object sender, RoutedEventArgs e) => await SendMessage();

    private async void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Return && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
        {
            e.Handled = true;
            await SendMessage();
        }
    }

    private async Task SendMessage()
    {
        if (_isStreaming) { _streamCts?.Cancel(); return; }
        var text = InputBox.Text.Trim();
        if (string.IsNullOrEmpty(text)) return;
        var service = GetSelectedService();
        if (service == null) return;
        var apiKey = Vm.AiSettings.GetApiKey(service);
        var model = GetModel();

        InputBox.Text = "";
        _messages.Add(new ChatMessage("user", text));
        AddBubble("user", text);

        _streamCts = new CancellationTokenSource();
        _isStreaming = true;
        SendBtn.Content = "Stop";

        var assistantBubble = AddBubble("assistant", "");
        var assistantText = (TextBlock)((Border)assistantBubble.Child).Child;
        var accumulated = "";

        try
        {
            await foreach (var chunk in Vm.SendChatMessageStream(service, apiKey, model, _messages, ct: _streamCts.Token))
            {
                accumulated += chunk;
                assistantText.Text = accumulated;
                MessagesScroll.ScrollToBottom();
            }
            _messages.Add(new ChatMessage("assistant", accumulated));
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            assistantText.Text = $"Error: {ex.Message}";
            assistantText.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
        }
        finally
        {
            _isStreaming = false;
            SendBtn.Content = "Send";
        }
    }

    private Border AddBubble(string role, string text)
    {
        var isUser = role == "user";
        var inner = new TextBlock
        {
            Text = text,
            TextWrapping = TextWrapping.Wrap,
            FontSize = 13,
            Foreground = isUser
                ? (SolidColorBrush)FindResource("OnBackgroundBrush")
                : (SolidColorBrush)FindResource("OnBackgroundBrush")
        };

        var bubble = new Border
        {
            Background = isUser
                ? (SolidColorBrush)FindResource("SidebarSelectedBrush")
                : (SolidColorBrush)FindResource("CardBackgroundBrush"),
            BorderBrush = (SolidColorBrush)FindResource("CardBorderBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12, 8, 12, 8),
            MaxWidth = 600,
            Child = inner
        };

        var wrapper = new Border
        {
            Margin = new Thickness(0, 4, 0, 4),
            HorizontalAlignment = isUser ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            Child = bubble
        };

        MessagesPanel.Children.Add(wrapper);
        MessagesScroll.ScrollToBottom();
        return wrapper;
    }

    private void NewSessionBtn_Click(object sender, RoutedEventArgs e)
    {
        _messages.Clear();
        MessagesPanel.Children.Clear();
        _streamCts?.Cancel();
        _isStreaming = false;
        SendBtn.Content = "Send";
    }
}
