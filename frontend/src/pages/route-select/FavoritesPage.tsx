import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { useLocation } from "react-router";
import { useAuth } from "@/shared/hooks/useAuth";
import { GuestFavoritesCard } from "./components/GuestFavoritesCard";
import { HomeFavorites } from "./components/HomeFavorites";
import * as styles from "./components/RouteSelectScreen.css";

export function FavoritesPage() {
  const { state: auth } = useAuth();
  const location = useLocation();
  const showGuest = auth.status !== "authenticated" && auth.status !== "loading";
  return (
    <section className={showGuest ? styles.centeredGuestFavorites : styles.favoritesSheetPage} aria-label="즐겨찾기">
      <h1 className={visuallyHidden}>즐겨찾기</h1>
      {auth.status === "authenticated" ? (
        <HomeFavorites key={`${auth.member.id}:${location.key}`} />
      ) : auth.status === "loading" ? (
        <p role="status">로그인 상태를 확인하고 있어요…</p>
      ) : (
        <GuestFavoritesCard />
      )}
    </section>
  );
}
