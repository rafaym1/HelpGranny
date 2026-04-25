import asyncio
import logging
import re

# Set up basic logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


async def run_subprocess(command: str) -> tuple[str, str]:
    """
    Executes a shell command in a subprocess.

    Args:
        command: The command to execute.

    Returns:
        A tuple containing the stdout and stderr of the command.
    """
    process = await asyncio.create_subprocess_shell(
        command,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    return stdout.decode(errors="ignore"), stderr.decode(errors="ignore")


async def get_accessibility_tree(device_id: str | None = None) -> str:
    """
    Retrieves the UI accessibility tree from an Android device as an XML string.

    This function uses `uiautomator` to dump the current UI hierarchy.

    Args:
        device_id: The optional ID of the target device. If not provided,
                   the command will run on the only connected device.

    Returns:
        The UI hierarchy as an XML string.
        Returns an empty string if the command fails.
    """
    adb_command = "adb"
    if device_id:
        adb_command = f"adb -s {device_id}"

    # The '/dev/tty' trick is used to get the raw XML output directly.
    # On some devices, '/dev/null' or a temporary file might be needed.
    command = f"{adb_command} shell uiautomator dump /dev/tty"

    logger.info(f"Executing command: {command}")

    try:
        stdout, stderr = await run_subprocess(command)

        if "UI hierchary dumped to" in stderr:  # Mispelling is in the original tool
            # The XML is often in stdout, but sometimes mixed with stderr
            # We'll clean it up to ensure we only get the XML part.
            xml_output = re.sub(r"UI hierchary dumped to.*", "", stderr, flags=re.DOTALL).strip()
            if not xml_output.startswith("<?xml"):
                xml_output = stdout

            # Clean up potential non-XML text at the beginning
            xml_start_index = xml_output.find("<?xml")
            if xml_start_index != -1:
                return xml_output[xml_start_index:].strip()
            else:
                logger.error("Could not find XML content in the output.")
                return ""

        elif "ERROR" in stderr:
            logger.error(f"Failed to get accessibility tree: {stderr.strip()}")
            return ""

        return stdout.strip()

    except Exception as e:
        logger.error(f"An exception occurred while getting the accessibility tree: {e}")
        return ""


# Example of how to run this function
async def main():
    print("Attempting to retrieve accessibility tree from the connected device...")
    # You can specify a device_id like "emulator-5554" if you have multiple devices
    accessibility_tree = await get_accessibility_tree()

    if accessibility_tree:
        print("\n--- Accessibility Tree XML ---")
        print(accessibility_tree)
        print("\n----------------------------")
    else:
        print("\nFailed to retrieve the accessibility tree.")


if __name__ == "__main__":
    # To run this example, save it as a Python file (e.g., `get_tree.py`)
    # and run `python get_tree.py` in your terminal.
    # Make sure you have an Android device connected with ADB enabled.
    asyncio.run(main())
