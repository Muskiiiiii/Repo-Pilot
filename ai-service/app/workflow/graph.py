import logging
from typing import Dict, Any, Literal
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.memory import MemorySaver

from app.workflow.state import EngineeringWorkflowState
from app.workflow.nodes import (
    understand_issue_node,
    retrieve_context_node,
    generate_plan_node,
    generate_code_node,
    validate_changes_node,
    execute_tests_node,
    diagnose_failure_node,
    repair_code_node,
    review_code_node,
    check_approval_node,
    finalize_workflow_node
)

logger = logging.getLogger("repopilot.ai.workflow.graph")

# Conditional Router Functions

def route_after_understanding(state: EngineeringWorkflowState) -> Literal["retrieve_context", "finalize_workflow"]:
    status = state.get("workflow_status", "")
    if status.startswith("FAILED"):
        return "finalize_workflow"
    return "retrieve_context"

def route_after_planning(state: EngineeringWorkflowState) -> Literal["generate_code", "finalize_workflow"]:
    status = state.get("workflow_status", "")
    if status.startswith("FAILED"):
        return "finalize_workflow"
    return "generate_code"

def route_after_code_generation(state: EngineeringWorkflowState) -> Literal["validate_changes", "finalize_workflow"]:
    status = state.get("workflow_status", "")
    if status.startswith("FAILED"):
        return "finalize_workflow"
    return "validate_changes"

def route_after_validation(state: EngineeringWorkflowState) -> Literal["execute_tests", "finalize_workflow"]:
    val_results = state.get("validation_results") or {}
    if not val_results.get("valid", False):
        logger.warning("[%s] Proposed changes failed validation: %s",
                       state.get("task_id"), val_results.get("errors"))
        return "finalize_workflow"
    return "execute_tests"

def route_after_testing(state: EngineeringWorkflowState) -> Literal["review_code", "diagnose_failure", "finalize_workflow"]:
    task_id = state.get("task_id", "unknown")
    test_results = state.get("test_results") or {}

    # Case 1: Infrastructure error -> terminate safely
    if test_results.get("is_infrastructure_error", False):
        logger.error("[%s] Test runner reported infrastructure failure. Halting workflow.", task_id)
        state["workflow_status"] = "FAILED_TEST_INFRASTRUCTURE"
        return "finalize_workflow"

    # Case 2: Tests passed -> proceed to code review
    if test_results.get("passed", False):
        logger.info("[%s] Tests passed successfully. Proceeding to AI Code Review.", task_id)
        return "review_code"

    # Case 3: Tests failed -> check bounded repair limit
    attempts = state.get("repair_attempts", 0)
    max_attempts = state.get("max_repair_attempts", 3)

    if attempts >= max_attempts:
        logger.warning("[%s] Maximum repair attempts reached (%d/%d). Terminating with failure.",
                       task_id, attempts, max_attempts)
        state["workflow_status"] = "FAILED_MAX_REPAIRS_REACHED"
        return "finalize_workflow"

    logger.info("[%s] Tests failed. Routing to diagnose_failure (attempt %d of %d).",
                task_id, attempts + 1, max_attempts)
    return "diagnose_failure"

def route_after_diagnosis(state: EngineeringWorkflowState) -> Literal["repair_code", "finalize_workflow"]:
    task_id = state.get("task_id", "unknown")
    diag = state.get("diagnosis") or {}
    rec = diag.get("repairRecommendation", "REPAIR")

    if rec == "DO_NOT_REPAIR":
        logger.warning("[%s] Diagnosis marked failure as non-repairable. Halting workflow.", task_id)
        state["workflow_status"] = "FAILED_NON_REPAIRABLE"
        return "finalize_workflow"

    return "repair_code"

# Graph Construction and Compilation

def build_engineering_graph(checkpointer: Any = None):
    """
    Constructs the RepoPilot LangGraph StateGraph orchestrating:
    START -> understand_issue -> retrieve_context -> generate_plan -> generate_code
          -> validate_changes -> execute_tests
                |-- (pass) --> review_code -> check_approval -> finalize_workflow -> END
                |-- (fail) --> diagnose_failure -> repair_code -> validate_changes (loop)
    """
    graph = StateGraph(EngineeringWorkflowState)

    # 1. Add all nodes
    graph.add_node("understand_issue", understand_issue_node)
    graph.add_node("retrieve_context", retrieve_context_node)
    graph.add_node("generate_plan", generate_plan_node)
    graph.add_node("generate_code", generate_code_node)
    graph.add_node("validate_changes", validate_changes_node)
    graph.add_node("execute_tests", execute_tests_node)
    graph.add_node("diagnose_failure", diagnose_failure_node)
    graph.add_node("repair_code", repair_code_node)
    graph.add_node("review_code", review_code_node)
    graph.add_node("check_approval", check_approval_node)
    graph.add_node("finalize_workflow", finalize_workflow_node)

    # 2. Set entry point
    graph.set_entry_point("understand_issue")

    # 3. Add edges and conditional routing
    graph.add_conditional_edges(
        "understand_issue",
        route_after_understanding,
        {
            "retrieve_context": "retrieve_context",
            "finalize_workflow": "finalize_workflow"
        }
    )

    graph.add_edge("retrieve_context", "generate_plan")

    graph.add_conditional_edges(
        "generate_plan",
        route_after_planning,
        {
            "generate_code": "generate_code",
            "finalize_workflow": "finalize_workflow"
        }
    )

    graph.add_conditional_edges(
        "generate_code",
        route_after_code_generation,
        {
            "validate_changes": "validate_changes",
            "finalize_workflow": "finalize_workflow"
        }
    )

    graph.add_conditional_edges(
        "validate_changes",
        route_after_validation,
        {
            "execute_tests": "execute_tests",
            "finalize_workflow": "finalize_workflow"
        }
    )

    graph.add_conditional_edges(
        "execute_tests",
        route_after_testing,
        {
            "review_code": "review_code",
            "diagnose_failure": "diagnose_failure",
            "finalize_workflow": "finalize_workflow"
        }
    )

    graph.add_conditional_edges(
        "diagnose_failure",
        route_after_diagnosis,
        {
            "repair_code": "repair_code",
            "finalize_workflow": "finalize_workflow"
        }
    )

    # Bounded Repair loop: after repair, re-validate changes and re-run tests
    graph.add_edge("repair_code", "validate_changes")

    # Post-test success path: review -> approval check -> finalize -> END
    graph.add_edge("review_code", "check_approval")
    graph.add_edge("check_approval", "finalize_workflow")
    graph.add_edge("finalize_workflow", END)

    # Compile with checkpointing
    if checkpointer is not None:
        return graph.compile(checkpointer=checkpointer)
    return graph.compile()

# Global checkpointer & compiled graph instance
memory_checkpointer = MemorySaver()
engineering_graph = build_engineering_graph(checkpointer=memory_checkpointer)
