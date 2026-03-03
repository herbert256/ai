using System.Windows;
using AI.Data;
using AI.ViewModels;

namespace AI;

public partial class App : Application
{
    public static AppViewModel ViewModel { get; private set; } = null!;

    private async void Application_Startup(object sender, StartupEventArgs e)
    {
        ViewModel = new AppViewModel();
        await ViewModel.Bootstrap();
    }
}
