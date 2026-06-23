import os
import csv
import pytest
from core.ledger import LedgerManager

def test_file_creation_and_header_writing(tmp_path):
    ledger_file = tmp_path / "test_ledger.csv"
    assert not os.path.exists(ledger_file)
    
    # Initialize LedgerManager
    manager = LedgerManager(filepath=str(ledger_file))
    
    # Verify file is created
    assert os.path.exists(ledger_file)
    
    # Verify header is written
    expected_header = [
        "id_rodada", "trecho", "refatoracao_tipo", "persona", "replica", "status",
        "complexity_base", "complexity_pos", "complexity_delta", "smells_base",
        "smells_pos", "smells_delta", "tempo_execucao_seg", "token_count"
    ]
    with open(ledger_file, mode='r', newline='', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader)
        assert header == expected_header

def test_get_completed_ids(tmp_path):
    ledger_file = tmp_path / "test_ledger.csv"
    
    # Initialize and write header
    manager = LedgerManager(filepath=str(ledger_file))
    
    # Initial status: completed ids should be empty
    assert manager.get_completed_ids() == set()
    
    # Now simulate existing files with some data
    row1 = {
        "id_rodada": 1, "trecho": "t1", "refatoracao_tipo": "r1", "persona": "p1",
        "replica": 1, "status": "success", "complexity_base": 5, "complexity_pos": 4,
        "complexity_delta": -1, "smells_base": 2, "smells_pos": 1, "smells_delta": -1,
        "tempo_execucao_seg": 10.5, "token_count": 100
    }
    row2 = {
        "id_rodada": "2", "trecho": "t2", "refatoracao_tipo": "r2", "persona": "p2",
        "replica": 2, "status": "success", "complexity_base": 8, "complexity_pos": 8,
        "complexity_delta": 0, "smells_base": 3, "smells_pos": 3, "smells_delta": 0,
        "tempo_execucao_seg": 12.0, "token_count": 150
    }
    
    manager.write_row(row1)
    manager.write_row(row2)
    
    # Verify we get the completed IDs (integers)
    assert manager.get_completed_ids() == {1, 2}

def test_write_row_appends_and_flushes(tmp_path):
    ledger_file = tmp_path / "test_ledger.csv"
    manager = LedgerManager(filepath=str(ledger_file))
    
    row_data = {
        "id_rodada": 42, "trecho": "some_method", "refatoracao_tipo": "Extract Method",
        "persona": "Expert", "replica": 3, "status": "failure", "complexity_base": 10,
        "complexity_pos": 12, "complexity_delta": 2, "smells_base": 4, "smells_pos": 5,
        "smells_delta": 1, "tempo_execucao_seg": 1.25, "token_count": 200
    }
    
    manager.write_row(row_data)
    
    # Check if the row was indeed appended
    with open(ledger_file, mode='r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        assert len(rows) == 1
        written_row = rows[0]
        
        # Verify values
        assert int(written_row["id_rodada"]) == 42
        assert written_row["trecho"] == "some_method"
        assert written_row["refatoracao_tipo"] == "Extract Method"
        assert written_row["persona"] == "Expert"
        assert int(written_row["replica"]) == 3
        assert written_row["status"] == "failure"
        assert int(written_row["complexity_base"]) == 10
        assert int(written_row["complexity_pos"]) == 12
        assert int(written_row["complexity_delta"]) == 2
        assert int(written_row["smells_base"]) == 4
        assert int(written_row["smells_pos"]) == 5
        assert int(written_row["smells_delta"]) == 1
        assert float(written_row["tempo_execucao_seg"]) == 1.25
        assert int(written_row["token_count"]) == 200

def test_get_completed_ids_handles_invalid_data(tmp_path):
    ledger_file = tmp_path / "test_ledger.csv"
    # Write manual csv data containing missing/invalid id_rodada
    expected_header = [
        "id_rodada", "trecho", "refatoracao_tipo", "persona", "replica", "status",
        "complexity_base", "complexity_pos", "complexity_delta", "smells_base",
        "smells_pos", "smells_delta", "tempo_execucao_seg", "token_count"
    ]
    with open(ledger_file, mode='w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(expected_header)
        writer.writerow(["3", "t3", "r3", "p3", "1", "success", "5", "4", "-1", "2", "1", "-1", "10.5", "100"])
        writer.writerow(["invalid_id", "t4", "r4", "p4", "1", "success", "5", "4", "-1", "2", "1", "-1", "10.5", "100"])
        writer.writerow(["", "t5", "r5", "p5", "1", "success", "5", "4", "-1", "2", "1", "-1", "10.5", "100"])
        writer.writerow(["5", "t6", "r6", "p6", "1", "success", "5", "4", "-1", "2", "1", "-1", "10.5", "100"])
        
    manager = LedgerManager(filepath=str(ledger_file))
    assert manager.get_completed_ids() == {3, 5}
