"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";

const loginSchema = z.object({
  username: z.string().trim().min(1, "Username wajib diisi."),
  password: z.string().min(8, "Password minimal 8 karakter."),
});

type LoginValues = z.infer<typeof loginSchema>;

/**
 * Sign-in form (FE §2.1). Both tokens stay in memory (ADR-023 D2) and the username is kept for the
 * header label because the JWT subject is a UUID. A 401 and a 423 `ACCOUNT_LOCKED` both render the
 * backend's own message, which already explains the lockout window.
 */
export function LoginForm() {
  const router = useRouter();
  const [failure, setFailure] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: "", password: "" },
  });

  async function onSubmit(values: LoginValues) {
    setFailure(null);
    try {
      await login(values.username, values.password);
      router.replace("/contracts");
    } catch (error) {
      setFailure(
        error instanceof ApiError ? error : new Error("Tidak dapat menghubungi server. Coba lagi."),
      );
    }
  }

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>Masuk</CardTitle>
        <CardDescription>Gunakan akun operasional Anda.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit(onSubmit)} noValidate>
          <div className="space-y-1.5">
            <Label htmlFor="username">Username</Label>
            <Input id="username" autoComplete="username" autoFocus {...register("username")} />
            {errors.username ? (
              <p className="text-xs text-destructive" role="alert">
                {errors.username.message}
              </p>
            ) : null}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              {...register("password")}
            />
            {errors.password ? (
              <p className="text-xs text-destructive" role="alert">
                {errors.password.message}
              </p>
            ) : null}
          </div>
          {failure === null ? null : <ErrorAlert error={failure} title="Gagal masuk" />}
          <Button type="submit" className="w-full" disabled={isSubmitting}>
            {isSubmitting ? "Memproses…" : "Masuk"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
