import subprocess


def run_shell_command_on_host(command: str) -> str:
    """Helper to run a shell command on the host and return the output."""
    try:
        result = subprocess.run(
            command,
            shell=True,
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        # Log the error and stderr for better debugging
        error_message = (
            f"Command '{command}' failed with exit code {e.returncode}.\nStderr: {e.stderr.strip()}"
        )
        raise RuntimeError(error_message) from e
