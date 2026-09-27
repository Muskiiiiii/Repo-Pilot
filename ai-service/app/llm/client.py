from abc import ABC, abstractmethod
import logging
from typing import Dict, Any, List, Optional
from app.config import settings

logger = logging.getLogger("repopilot.ai.llm")

class LLMProvider(ABC):
    """Abstract LLM Provider interface allowing vendor-agnostic models."""

    @abstractmethod
    async def generate_completion(self, prompt: str, system_prompt: Optional[str] = None) -> str:
        pass

    @abstractmethod
    async def generate_structured_output(self, prompt: str, schema: Dict[str, Any], system_prompt: Optional[str] = None) -> Dict[str, Any]:
        pass

class MockLLMProvider(LLMProvider):
    """Mock LLM Provider for unit tests and fallback executions."""

    async def generate_completion(self, prompt: str, system_prompt: Optional[str] = None) -> str:
        logger.info("MockLLMProvider generate_completion invoked")
        return "Mock response for prompt: " + prompt[:50]

    async def generate_structured_output(self, prompt: str, schema: Dict[str, Any], system_prompt: Optional[str] = None) -> Dict[str, Any]:
        logger.info("MockLLMProvider generate_structured_output invoked")
        return {"status": "success", "mock": True}

class OpenAIProvider(LLMProvider):
    """OpenAI API implementation of LLMProvider."""

    def __init__(self, api_key: str, model: str):
        self.api_key = api_key
        self.model = model

    async def generate_completion(self, prompt: str, system_prompt: Optional[str] = None) -> str:
        logger.info("OpenAIProvider invoking model: %s", self.model)
        # Integration point for OpenAI / HTTP Client
        if self.api_key == "placeholder":
            return f"[Simulated LLM Output from {self.model}] Processed request."
        return f"[LLM Response from {self.model}]"

    async def generate_structured_output(self, prompt: str, schema: Dict[str, Any], system_prompt: Optional[str] = None) -> Dict[str, Any]:
        logger.info("OpenAIProvider generating structured output for model: %s", self.model)
        return {"model": self.model, "result": "structured_data"}

def get_llm_provider() -> LLMProvider:
    if settings.LLM_API_KEY == "placeholder" or not settings.LLM_API_KEY:
        logger.info("Using MockLLMProvider (No valid API key provided)")
        return MockLLMProvider()
    return OpenAIProvider(api_key=settings.LLM_API_KEY, model=settings.LLM_MODEL)
