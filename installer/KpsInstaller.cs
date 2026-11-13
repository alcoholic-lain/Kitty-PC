using System;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

internal static class KpsInstaller
{
    [STAThread]
    private static void Main()
    {
        Application.EnableVisualStyles();

        try
        {
            string serverDirectory = SelectServerDirectory();
            if (serverDirectory == null)
            {
                return;
            }

            Install(serverDirectory);
            MessageBox.Show(
                "kps is installed for your Windows PowerShell and PowerShell 7 profiles.\r\n" +
                "Close and reopen PowerShell, then run: kps -s",
                "Kitty-PC kps installer",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                exception.Message,
                "Kitty-PC kps installer",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private static string SelectServerDirectory()
    {
        string initialDirectory = null;
        string startupDirectory = Application.StartupPath;
        string parentDirectory = Directory.GetParent(startupDirectory) == null
            ? startupDirectory
            : Directory.GetParent(startupDirectory).FullName;
        string[] candidates =
        {
            startupDirectory,
            parentDirectory,
            Path.Combine(parentDirectory, "server")
        };

        foreach (string candidate in candidates)
        {
            if (File.Exists(Path.Combine(candidate, "server.py")))
            {
                initialDirectory = candidate;
                break;
            }
        }

        using (FolderBrowserDialog dialog = new FolderBrowserDialog())
        {
            dialog.Description = "Select the Kitty-PC server folder containing server.py.";
            dialog.ShowNewFolderButton = false;
            if (initialDirectory != null)
            {
                dialog.SelectedPath = initialDirectory;
            }

            if (dialog.ShowDialog() != DialogResult.OK)
            {
                return null;
            }

            string selectedDirectory = Path.GetFullPath(dialog.SelectedPath);
            if (!File.Exists(Path.Combine(selectedDirectory, "server.py")))
            {
                throw new InvalidOperationException(
                    "The selected folder does not contain server.py.");
            }

            return selectedDirectory;
        }
    }

    private static void Install(string serverDirectory)
    {
        string localAppData = Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData);
        string installDirectory = Path.Combine(localAppData, "Kitty-PC");
        Directory.CreateDirectory(installDirectory);

        string profileScript = Path.Combine(installDirectory, "kps.ps1");
        string scriptTemplate;
        using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(
            "KittyPC.KpsProfile.ps1"))
        {
            if (stream == null)
            {
                throw new InvalidOperationException("The embedded kps profile script is missing.");
            }

            using (StreamReader reader = new StreamReader(stream))
            {
                scriptTemplate = reader.ReadToEnd();
            }
        }

        string escapedServerDirectory = serverDirectory.Replace("'", "''");
        string scriptContents = scriptTemplate.Replace(
            "__KPS_SERVER_DIRECTORY__",
            escapedServerDirectory);
        File.WriteAllText(profileScript, scriptContents, new UTF8Encoding(false));

        string escapedProfileScript = profileScript.Replace("'", "''");
        string profileLine = ". '" + escapedProfileScript + "'";
        string documentsDirectory = Environment.GetFolderPath(
            Environment.SpecialFolder.MyDocuments);
        string[] profileDirectories =
        {
            Path.Combine(documentsDirectory, "WindowsPowerShell"),
            Path.Combine(documentsDirectory, "PowerShell")
        };

        foreach (string profileDirectory in profileDirectories)
        {
            Directory.CreateDirectory(profileDirectory);
            string profilePath = Path.Combine(profileDirectory, "profile.ps1");
            string existingContents = File.Exists(profilePath)
                ? File.ReadAllText(profilePath)
                : String.Empty;

            if (existingContents.IndexOf(profileLine, StringComparison.OrdinalIgnoreCase) < 0)
            {
                string separator = existingContents.Length > 0 &&
                    !existingContents.EndsWith("\r\n", StringComparison.Ordinal) &&
                    !existingContents.EndsWith("\n", StringComparison.Ordinal)
                    ? Environment.NewLine
                    : String.Empty;
                File.AppendAllText(
                    profilePath,
                    separator + profileLine + Environment.NewLine,
                    new UTF8Encoding(false));
            }
        }
    }
}
