using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using AI.ViewModels;

namespace AI.Views.Reports;

public partial class ReportProgressView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public ReportProgressView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        Refresh();
        if (Vm is INotifyPropertyChanged npc)
            npc.PropertyChanged += OnVmChanged;
    }

    private void OnVmChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(AppViewModel.GenericReportsProgress)
            or nameof(AppViewModel.GenericReportsTotal)
            or nameof(AppViewModel.GenericReportsAgentResults)
            or nameof(AppViewModel.GenericPromptTitle))
        {
            Dispatcher.Invoke(Refresh);
        }
    }

    private void Refresh()
    {
        TitleText.Text = string.IsNullOrEmpty(Vm.GenericPromptTitle) ? "Report Progress" : Vm.GenericPromptTitle;
        var progress = Vm.GenericReportsTotal > 0 ? Vm.GenericReportsProgress : 0;
        var total = Vm.GenericReportsTotal;
        StatusText.Text = $"{progress} / {total} completed";
        ProgressBar.Maximum = total > 0 ? total : 1;
        ProgressBar.Value = progress;
        ResultsList.ItemsSource = Vm.GenericReportsAgentResults.Values.ToList();
    }

    private void StopBtn_Click(object sender, RoutedEventArgs e) => Vm.StopGenericReports();

    private void DismissBtn_Click(object sender, RoutedEventArgs e)
    {
        Vm.DismissGenericReportsDialog();
        Vm.SelectedSection = SidebarSection.ReportHistory;
    }
}
