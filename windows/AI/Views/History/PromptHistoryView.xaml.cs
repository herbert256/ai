using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.Helpers;
using AI.ViewModels;

namespace AI.Views.History;

public partial class PromptHistoryView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public PromptHistoryView()
    {
        InitializeComponent();
        Loaded += (_, _) => LoadHistory();
    }

    private void LoadHistory()
    {
        HistoryList.ItemsSource = SettingsPreferences.LoadPromptHistory();
    }

    private void HistoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (HistoryList.SelectedItem is not PromptHistoryEntry entry) return;
        DetailTitle.Text = entry.Title;
        DetailPromptText.Text = entry.Prompt;
    }

    private void RefreshBtn_Click(object sender, RoutedEventArgs e) => LoadHistory();

    private void UsePromptBtn_Click(object sender, RoutedEventArgs e)
    {
        if (HistoryList.SelectedItem is not PromptHistoryEntry entry) return;
        Vm.GenericPromptTitle = entry.Title;
        Vm.GenericPromptText = entry.Prompt;
        Vm.SelectedSection = SidebarSection.NewReport;
    }
}
