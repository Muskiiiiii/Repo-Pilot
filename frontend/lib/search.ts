import { apiFetch } from './api';

export interface SearchResultItem {
  chunkId: string;
  filePath: string;
  fileLanguage: string;
  symbolName: string;
  chunkType: string;
  startLine: number;
  endLine: number;
  content: string;
  distance: number;
  similarityScore: number;
}

export interface SearchResponse {
  query: string;
  topK: number;
  totalResults: number;
  results: SearchResultItem[];
}

export async function searchSemanticCode(
  projectId: string,
  repositoryId: string,
  query: string,
  topK: number = 10
): Promise<SearchResponse> {
  return apiFetch<SearchResponse>(
    `/projects/${projectId}/repositories/${repositoryId}/search`,
    {
      method: 'POST',
      body: JSON.stringify({ query, topK }),
    }
  );
}
