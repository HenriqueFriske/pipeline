import os
import csv

class LedgerManager:
    FIELDNAMES = [
        "id_rodada",
        "trecho",
        "refatoracao_tipo",
        "persona",
        "replica",
        "status",
        "complexity_base",
        "complexity_pos",
        "complexity_delta",
        "smells_base",
        "smells_pos",
        "smells_delta",
        "tempo_execucao_seg",
        "token_count"
    ]

    def __init__(self, filepath: str = "checkpoint_ledger.csv"):
        self.filepath = filepath
        
        # Ensure parent directory exists if a path with directories is provided
        dirname = os.path.dirname(os.path.abspath(self.filepath))
        if dirname:
            os.makedirs(dirname, exist_ok=True)
            
        # Initialize file: If filepath does not exist, create it and write the CSV header
        if not os.path.exists(self.filepath):
            with open(self.filepath, mode='w', newline='', encoding='utf-8') as f:
                writer = csv.writer(f)
                writer.writerow(self.FIELDNAMES)
                f.flush()
                os.fsync(f.fileno())

    def get_completed_ids(self) -> set[int]:
        """Read the CSV file and return a set of completed id_rodada (integers)."""
        completed = set()
        if not os.path.exists(self.filepath):
            return completed

        with open(self.filepath, mode='r', newline='', encoding='utf-8') as f:
            reader = csv.DictReader(f)
            for row in reader:
                val = row.get("id_rodada")
                if val is not None and val != "":
                    try:
                        completed.add(int(val))
                    except ValueError:
                        pass
        return completed

    def write_row(self, row_data: dict) -> None:
        """Append a row of data represented by row_data dictionary to the CSV file."""
        with open(self.filepath, mode='a', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=self.FIELDNAMES, extrasaction='ignore')
            writer.writerow(row_data)
            f.flush()
            os.fsync(f.fileno())
