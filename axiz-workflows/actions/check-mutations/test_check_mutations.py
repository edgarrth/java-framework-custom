from pathlib import Path
import tempfile
import unittest
from check_mutations import check


class MutationProcessesTest(unittest.TestCase):
    def test_crashes_fail_even_when_pit_marks_them_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'module/target/pit-reports/mutations.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<mutations><mutation status="KILLED" detected="true"/>'
                              '<mutation status="TIMED_OUT" detected="true"/></mutations>')
            self.assertEqual(0, check(directory))
            for status in ['RUN_ERROR', 'MEMORY_ERROR', 'NOT_STARTED', 'STARTED']:
                report.write_text(f'<mutations><mutation status="{status}" detected="true"/></mutations>')
                self.assertEqual(1, check(directory))


if __name__ == '__main__':
    unittest.main()
