export interface WhatsNewNote {
  id: number;
  publishedAt: string;
  features: readonly string[];
}

export const WHATS_NEW_NOTES: readonly WhatsNewNote[] = [];
