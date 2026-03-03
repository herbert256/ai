using System.Windows;
using System.Windows.Controls;
using AI.ViewModels;

namespace AI.Views.Reports;

public partial class NewReportView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public NewReportView()
    {
        InitializeComponent();
    }

    private void GenerateBtn_Click(object sender, RoutedEventArgs e)
    {
        var title = TitleBox.Text.Trim();
        var prompt = PromptBox.Text.Trim();

        if (string.IsNullOrEmpty(prompt))
        {
            MessageBox.Show("Please enter a prompt.", "Missing Prompt",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        Vm.ShowGenericAgentSelection(title, prompt);
        Vm.SelectedSection = SidebarSection.NewReport;
    }

    private void ClearBtn_Click(object sender, RoutedEventArgs e)
    {
        TitleBox.Text = "";
        PromptBox.Text = "";
    }
}
