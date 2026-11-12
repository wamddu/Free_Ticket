import { useState } from "react";
import { resetPasswordApi, deleteAccountApi } from "../../api/auth";
import axios from "axios";

export default function PasswordReset() {
  const [currentPassword, setCurrentPassword] = useState<string>("");
  const [newPassword, setNewPassword] = useState<string>("");
  const [newPasswordRe, setNewPasswordRe] = useState<string>("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);
  const [deleteInputPassword, setDeleteInputPassword] = useState<string>("");
  const [deleteInputErrorMessage, setDeleteInputErrorMessage] = useState<string | null>(null);

  const handleChangeCurrentPassword = (value: string) => {
    setCurrentPassword(value);
  }

  const handleChangeNewPassword = (value: string) => {
    setNewPassword(value);
  }

  const handleChangeNewPasswordRe = (value: string) => {
    setNewPasswordRe(value);
  }

  const handleChangeDeleteInputPassword = (value: string) => {
    setDeleteInputPassword(value);
  }

  const handleClickResetPassword = async () => {

    if (newPassword.trim() === "" ||
      currentPassword.trim() === "" ||
      newPasswordRe.trim() === "") {
      setErrorMessage("비밀번호를 채우셔야 합니다.");
      return;
    }

    if (newPassword.trim().length < 8 ||
      currentPassword.trim().length < 8 ||
      newPasswordRe.trim().length < 8) {
      setErrorMessage("비밀번호는 8자 이상이어야 합니다.");
      return;
    }

    if (newPassword !== newPasswordRe) {
      setErrorMessage("재입력이 일치하지 않습니다.");
      return;
    }

    try {
      const response = await resetPasswordApi({
        currentPassword: currentPassword,
        newPassword: newPassword,
      });
      alert(response.message);

      setCurrentPassword("");
      setNewPassword("");
      setNewPasswordRe("");
      setErrorMessage(null);
    } catch (error) {
      if (axios.isAxiosError(error)) {
        const message = error.response?.data?.message ?? "예상치 못한 오류가 발생했습니다.";
        setErrorMessage(message);
      }
    }
  }

  const handleClickDeletAccount = () => {
    setIsModalOpen(true);
  }

  const deleteAccount = async () => {
    try {
      await deleteAccountApi({
        password: deleteInputPassword
      });

      alert("회원탈퇴가 완료되었습니다.");
      setIsModalOpen(false);
      window.location.replace("/");
    } catch (error) {
      if (axios.isAxiosError(error)) {
        const message = error.response?.data?.message ?? "예상치 못한 오류가 발생했습니다.";
        setDeleteInputErrorMessage(message);
      }
    }
  }

  return (
    <section>
      {isModalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4"
          onClick={() => setIsModalOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl bg-white p-7 shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-2xl font-bold text-gray-900">
                  회원탈퇴
                </h2>
                <p className="mt-2 text-sm leading-6 text-gray-500">
                  정말 회원탈퇴를 원하시면 현재 비밀번호를 입력해주세요
                </p>
              </div>

              <button
                type="button"
                onClick={() => setIsModalOpen(false)}
                className="rounded-lg p-2 text-gray-400 transition hover:bg-gray-100 hover:text-gray-700"
                aria-label="모달 닫기"
              >
                ✕
              </button>
            </div>

            <div className="mt-7">
              <label
                htmlFor="reset-email"
                className="mb-2 block text-sm font-medium text-gray-700"
              >
                비밀번호
              </label>

              <input
                id="reset-email"
                type="password"
                value={deleteInputPassword}
                onChange={(e) => handleChangeDeleteInputPassword(e.target.value)}
                className="w-full rounded-xl border border-gray-300 px-4 py-3 text-sm outline-none transition placeholder:text-gray-400 focus:border-black focus:ring-2 focus:ring-black/10"
              />
              {deleteInputErrorMessage && <div className="text-red-400 text-sm ml-2">{deleteInputErrorMessage}</div>}
            </div>

            <div className="mt-7 flex gap-3">
              <button
                type="button"
                onClick={() => setIsModalOpen(false)}
                className="flex-1 rounded-xl border border-gray-300 px-4 py-3 font-medium text-gray-700 transition hover:bg-gray-50"
              >
                취소
              </button>

              <button
                type="button"
                onClick={deleteAccount}
                className="flex-1 rounded-xl bg-red-400 px-4 py-3 font-medium text-white transition hover:bg-red-500"
              >
                회원탈퇴
              </button>
            </div>

            <p className="mt-5 text-center text-xs text-gray-400">
              이메일이 도착하지 않으면 스팸 메일함을 확인해주세요.
            </p>
          </div>
        </div>
      )}
      <h1 className="text-2xl font-bold">비밀번호 재설정</h1>
      <p className="mt-2 text-sm text-gray-500">
        비밀번호를 재설정 할 수 있습니다.
      </p>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleClickResetPassword();
        }}
        className="mt-6 max-w-xl space-y-5">

        <div>
          <label
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            현재 비밀번호
          </label>
          <input
            value={currentPassword}
            type="password"
            onChange={(e) => handleChangeCurrentPassword(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
        </div>

        <div>
          <label
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            새 비밀번호
          </label>
          <input
            value={newPassword}
            type="password"
            onChange={(e) => handleChangeNewPassword(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
        </div>

        <div>
          <label
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            새 비밀번호 재입력
          </label>
          <input
            value={newPasswordRe}
            type="password"
            onChange={(e) => handleChangeNewPasswordRe(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
        </div>

        {errorMessage && <div className="text-red-400 text-sm ml-2">{errorMessage}</div>}

        <button
          type="submit"
          className="rounded-lg bg-black px-6 py-3 text-white transition-colors hover:bg-gray-800"
        >
          재설정
        </button>
      </form>

      <button 
        className="mt-20 rounded-lg bg-red-500 px-6 py-3 text-white transition-colors hover:bg-red-400"
        onClick={handleClickDeletAccount}
      >
        회원탈퇴
      </button>
    </section>
  );
}