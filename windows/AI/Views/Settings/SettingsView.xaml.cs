using System.Windows;
using System.Windows.Controls;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class SettingsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public SettingsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var gs = Vm.GeneralSettings;
        UserNameBox.Text = gs.UserName;
        DeveloperModeCheck.IsChecked = gs.DeveloperMode;
        HfKeyBox.Text = gs.HuggingFaceApiKey;
        OrKeyBox.Text = gs.OpenRouterApiKey;
        EmailBox.Text = gs.DefaultEmail;
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        var gs = new GeneralSettings
        {
            UserName = UserNameBox.Text.Trim(),
            DeveloperMode = DeveloperModeCheck.IsChecked == true,
            HuggingFaceApiKey = HfKeyBox.Text.Trim(),
            OpenRouterApiKey = OrKeyBox.Text.Trim(),
            DefaultEmail = EmailBox.Text.Trim()
        };
        Vm.UpdateGeneralSettings(gs);
        MessageBox.Show("Settings saved.", "Saved", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
