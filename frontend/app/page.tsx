import { redirect } from "next/navigation";

/** The app has no landing screen of its own; the contract book is the entry point (FE §2.2). */
export default function Home() {
  redirect("/contracts");
}
